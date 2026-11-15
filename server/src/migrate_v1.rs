use crate::auth::session::ts;
use crate::db::{Db, DbError};
use chrono::{DateTime, NaiveDateTime, Utc};
use chrono_tz::Tz;
use rusqlite::{Connection, OpenFlags, OptionalExtension, Transaction, params};
use std::collections::HashMap;
use std::path::Path;

#[derive(Debug, thiserror::Error)]
pub enum ImportError {
    #[error("{0}")]
    Msg(String),
    #[error(transparent)]
    Db(#[from] DbError),
}

fn msg<T>(s: impl Into<String>) -> Result<T, ImportError> {
    Err(ImportError::Msg(s.into()))
}

pub struct Options {
    pub household: String,
    /// (old name, v2 username)
    pub map: Vec<(String, String)>,
    pub force: bool,
    pub tz: Tz,
}

#[derive(Debug, Default)]
pub struct Summary {
    pub household: String,
    pub household_id: i64,
    pub chores: usize,
    pub categories: usize,
    pub completions: usize,
    pub skipped_missing_chore: usize,
    pub skipped_bad_date: usize,
    pub members: Vec<String>,
    pub former: Vec<String>,
    /// v1 names without --map and without importable history: nothing was created for them.
    pub not_imported: Vec<String>,
}

impl std::fmt::Display for Summary {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        writeln!(
            f,
            "Imported household \"{}\" (id {})",
            self.household, self.household_id
        )?;
        writeln!(f, "  chores:      {}", self.chores)?;
        writeln!(f, "  categories:  {}", self.categories)?;
        writeln!(f, "  completions: {}", self.completions)?;
        writeln!(
            f,
            "  skipped:     {} (no such chore: {}, bad date: {})",
            self.skipped_missing_chore + self.skipped_bad_date,
            self.skipped_missing_chore,
            self.skipped_bad_date
        )?;
        writeln!(f, "  members:     {}", self.members.join(", "))?;
        writeln!(f, "  former:      {}", self.former.join(", "))?;
        write!(
            f,
            "  not imported (no --map, no dated history): {}",
            self.not_imported.join(", ")
        )
    }
}

pub fn parse_map(args: &[String]) -> Result<Vec<(String, String)>, ImportError> {
    let mut out: Vec<(String, String)> = vec![];
    for arg in args {
        for part in arg.split(',').map(str::trim).filter(|p| !p.is_empty()) {
            let Some((old, user)) = part.split_once('=') else {
                return msg(format!("bad --map entry \"{part}\": use old=username"));
            };
            let (old, user) = (old.trim(), user.trim());
            if old.is_empty() || user.is_empty() {
                return msg(format!("bad --map entry \"{part}\": use old=username"));
            }
            if out
                .iter()
                .any(|(o, _)| o.to_lowercase() == old.to_lowercase())
            {
                return msg(format!("--map names \"{old}\" twice"));
            }
            out.push((old.to_string(), user.to_lowercase()));
        }
    }
    Ok(out)
}

pub async fn run(
    db: &Db,
    v1: &Path,
    opts: Options,
    now: DateTime<Utc>,
) -> Result<Summary, ImportError> {
    let uri = format!(
        "file:{}?mode=ro",
        v1.to_string_lossy()
            .replace('%', "%25")
            .replace('?', "%3f")
            .replace('#', "%23")
    );
    let v1_path = v1.display().to_string();
    db.run(move |c| {
        let src = match Connection::open_with_flags(
            &uri,
            OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_URI,
        ) {
            Ok(s) => s,
            Err(e) => return Ok(msg(format!("cannot open {v1_path}: {e}"))),
        };
        import(c, &src, &opts, now)
    })
    .await?
}

fn clip(s: &str, max: usize) -> String {
    s.trim().chars().take(max).collect()
}

fn parse_done_at(raw: &str) -> Option<DateTime<Utc>> {
    let raw = raw.trim();
    if let Ok(t) = DateTime::parse_from_rfc3339(raw) {
        return Some(t.with_timezone(&Utc));
    }
    for f in ["%Y-%m-%d %H:%M:%S%.f%#z", "%Y-%m-%dT%H:%M:%S%.f%#z"] {
        if let Ok(t) = DateTime::parse_from_str(raw, f) {
            return Some(t.with_timezone(&Utc));
        }
    }
    for f in ["%Y-%m-%d %H:%M:%S%.f", "%Y-%m-%dT%H:%M:%S%.f", "%Y-%m-%d"] {
        if let Ok(t) = NaiveDateTime::parse_from_str(raw, f) {
            return Some(t.and_utc());
        }
    }
    chrono::NaiveDate::parse_from_str(raw, "%Y-%m-%d")
        .ok()
        .map(|d| d.and_hms_opt(12, 0, 0).unwrap().and_utc())
}

fn uuid_v4() -> rusqlite::Result<String> {
    let mut b = [0u8; 16];
    getrandom::fill(&mut b)
        .map_err(|e| rusqlite::Error::ToSqlConversionFailure(e.to_string().into()))?;
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    let h: String = b.iter().map(|x| format!("{x:02x}")).collect();
    Ok(format!(
        "{}-{}-{}-{}-{}",
        &h[0..8],
        &h[8..12],
        &h[12..16],
        &h[16..20],
        &h[20..32]
    ))
}

struct V1Household {
    id: i64,
    name: String,
}

fn find_household(
    src: &Connection,
    wanted: &str,
) -> rusqlite::Result<Result<V1Household, ImportError>> {
    let mut all = src.prepare("SELECT Id, Name FROM Households ORDER BY Id")?;
    let list: Vec<V1Household> = all
        .query_map([], |r| {
            Ok(V1Household {
                id: r.get(0)?,
                name: r.get(1)?,
            })
        })?
        .collect::<rusqlite::Result<_>>()?;
    let wanted = wanted.trim();
    if let Ok(id) = wanted.parse::<i64>()
        && let Some(h) = list.iter().find(|h| h.id == id)
    {
        return Ok(Ok(V1Household {
            id: h.id,
            name: h.name.clone(),
        }));
    }
    let mut by_name = list.iter().filter(|h| h.name == wanted);
    let (first, second) = (by_name.next(), by_name.next());
    let describe = |hs: Vec<&V1Household>| {
        hs.iter()
            .map(|h| format!("  {}: {}", h.id, h.name))
            .collect::<Vec<_>>()
            .join("\n")
    };
    Ok(match (first, second) {
        (Some(h), None) => Ok(V1Household {
            id: h.id,
            name: h.name.clone(),
        }),
        (Some(_), Some(_)) => msg(format!(
            "household name \"{wanted}\" is ambiguous, use the id:\n{}",
            describe(list.iter().filter(|h| h.name == wanted).collect())
        )),
        _ => msg(format!(
            "no household \"{wanted}\" in the v1 file; available:\n{}",
            describe(list.iter().collect())
        )),
    })
}

struct Names {
    order: Vec<String>,
    index: HashMap<String, usize>,
}

impl Names {
    fn add(&mut self, name: &str) -> String {
        let key = name.to_lowercase();
        if let Some(i) = self.index.get(&key) {
            return self.order[*i].clone();
        }
        self.index.insert(key, self.order.len());
        self.order.push(name.to_string());
        name.to_string()
    }
}

struct History {
    chore: i64,
    who: String,
    done_at: Option<DateTime<Utc>>,
    note: Option<String>,
}

fn import(
    dst: &mut Connection,
    src: &Connection,
    o: &Options,
    now: DateTime<Utc>,
) -> rusqlite::Result<Result<Summary, ImportError>> {
    let hh = match find_household(src, &o.household)? {
        Ok(h) => h,
        Err(e) => return Ok(Err(e)),
    };
    let hh_name = clip(&hh.name, 60);
    if !o.force
        && dst.query_row(
            "SELECT EXISTS (SELECT 1 FROM households WHERE ulower(name) = ulower(?1))",
            [&hh_name],
            |r| r.get::<_, bool>(0),
        )?
    {
        return Ok(msg(format!(
            "a household named \"{hh_name}\" already exists in v2; use --force to import it again as another household"
        )));
    }

    let mut names = Names {
        order: vec![],
        index: HashMap::new(),
    };
    let mut members: HashMap<i64, String> = HashMap::new();
    {
        let mut s =
            src.prepare("SELECT Id, Username FROM Members WHERE HouseholdId = ?1 ORDER BY Id")?;
        for r in s.query_map([hh.id], |r| {
            Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?))
        })? {
            let (id, u) = r?;
            let u = u.trim().to_string();
            if !u.is_empty() {
                let canon = names.add(&u);
                members.insert(id, canon);
            }
        }
    }

    let mut chore_ids: Vec<i64> = vec![];
    let mut chores = vec![];
    {
        let mut s = src.prepare(
            "SELECT Id, Title, Room, CreatedAt FROM Chores WHERE HouseholdId = ?1 ORDER BY Id",
        )?;
        for r in s.query_map([hh.id], |r| {
            Ok((
                r.get::<_, i64>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, Option<String>>(2)?,
                r.get::<_, Option<String>>(3)?,
            ))
        })? {
            let r = r?;
            chore_ids.push(r.0);
            chores.push(r);
        }
    }

    let mut sum = Summary {
        household: hh_name.clone(),
        ..Default::default()
    };
    let mut hist: Vec<History> = vec![];
    {
        let mut s = src.prepare(
            "SELECT h.ChoreId, h.MemberId, h.MemberName, h.DoneAt, h.Notes,
                    EXISTS (SELECT 1 FROM Chores c WHERE c.Id = h.ChoreId)
             FROM ChoreHistories h ORDER BY h.DoneAt, h.Id",
        )?;
        let rows = s.query_map([], |r| {
            Ok((
                r.get::<_, i64>(0)?,
                r.get::<_, Option<i64>>(1)?,
                r.get::<_, Option<String>>(2)?,
                r.get::<_, Option<String>>(3)?,
                r.get::<_, Option<String>>(4)?,
                r.get::<_, bool>(5)?,
            ))
        })?;
        for r in rows {
            let (chore, member_id, member_name, done_at, notes, chore_exists) = r?;
            let own_member = member_id.and_then(|m| members.get(&m).cloned());
            if !chore_exists {
                if own_member.is_some() {
                    sum.skipped_missing_chore += 1;
                }
                continue;
            }
            if !chore_ids.contains(&chore) {
                continue;
            }
            let free = member_name.as_deref().map(str::trim).unwrap_or("");
            let who = match own_member {
                Some(m) => m,
                None if !free.is_empty() => names.add(free),
                None => names.add("Unknown"),
            };
            let when = done_at.as_deref().and_then(parse_done_at);
            let note = notes.map(|n| clip(&n, 500)).filter(|n| !n.is_empty());
            hist.push(History {
                chore,
                who,
                done_at: when,
                note,
            });
        }
    }

    for (old, _) in &o.map {
        if !names.index.contains_key(&old.to_lowercase()) {
            return Ok(msg(format!(
                "--map names \"{old}\", which is not in the v1 data; names there: {}",
                names.order.join(", ")
            )));
        }
    }
    let mut user_ids: HashMap<String, (i64, String)> = HashMap::new();
    let mut missing = vec![];
    for (old, user) in &o.map {
        let row: Option<(i64, String)> = dst
            .query_row(
                "SELECT id, username FROM users
                 WHERE username = ?1 AND former = 0 AND deleted_at IS NULL",
                [user],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .optional()?;
        match row {
            Some(r) => {
                user_ids.insert(old.to_lowercase(), r);
            }
            None => missing.push(user.clone()),
        }
    }
    if !missing.is_empty() {
        missing.dedup();
        return Ok(msg(format!(
            "these v2 users do not exist (or are not active): {}; create them first with: wg-chores create-user <username>",
            missing.join(", ")
        )));
    }

    let tx = dst.transaction()?;
    let done = write(
        &tx, o, now, &hh_name, &chores, &hist, &names, &user_ids, sum,
    )?;
    tx.commit()?;
    Ok(Ok(done))
}

#[allow(clippy::too_many_arguments)]
fn write(
    tx: &Transaction,
    o: &Options,
    now: DateTime<Utc>,
    hh_name: &str,
    chores: &[(i64, String, Option<String>, Option<String>)],
    hist: &[History],
    names: &Names,
    user_ids: &HashMap<String, (i64, String)>,
    mut sum: Summary,
) -> rusqlite::Result<Summary> {
    let now_s = ts(now);
    tx.execute(
        "INSERT INTO households (name, created_at) VALUES (?1, ?2)",
        params![hh_name, now_s],
    )?;
    let hid = tx.last_insert_rowid();
    sum.household_id = hid;

    let mut who_id: HashMap<String, i64> = HashMap::new();
    for name in &names.order {
        let key = name.to_lowercase();
        if let Some((uid, username)) = user_ids.get(&key) {
            who_id.insert(key, *uid);
            tx.execute(
                "INSERT OR IGNORE INTO memberships (household_id, user_id, joined_at) VALUES (?1, ?2, ?3)",
                params![hid, uid, now_s],
            )?;
            tx.execute(
                "UPDATE users SET last_household_id = ?2 WHERE id = ?1 AND last_household_id IS NULL",
                params![uid, hid],
            )?;
            if !sum.members.contains(username) {
                sum.members.push(username.clone());
            }
        } else if hist
            .iter()
            .any(|h| h.done_at.is_some() && h.who.to_lowercase() == key)
        {
            tx.execute(
                "INSERT INTO users (display_name, former, created_at, deleted_at) VALUES (?1, 1, ?2, ?2)",
                params![clip(name, 60), now_s],
            )?;
            who_id.insert(key, tx.last_insert_rowid());
            sum.former.push(name.clone());
        } else {
            sum.not_imported.push(name.clone());
        }
    }

    let mut cats: HashMap<String, i64> = HashMap::new();
    let mut chore_map: HashMap<i64, i64> = HashMap::new();
    for (old_id, title, room, created) in chores {
        let room = room
            .as_deref()
            .map(|r| clip(r, 60))
            .filter(|r| !r.is_empty());
        let room = room.unwrap_or_else(|| "Other".into());
        let cat = match cats.get(&room.to_lowercase()) {
            Some(c) => *c,
            None => {
                tx.execute(
                    "INSERT INTO categories (household_id, name) VALUES (?1, ?2)",
                    params![hid, room],
                )?;
                let id = tx.last_insert_rowid();
                cats.insert(room.to_lowercase(), id);
                id
            }
        };
        let name = Some(clip(title, 60))
            .filter(|n| !n.is_empty())
            .unwrap_or_else(|| "Untitled".into());
        let created = created
            .as_deref()
            .and_then(parse_done_at)
            .map_or_else(|| now_s.clone(), ts);
        tx.execute(
            "INSERT INTO chores (household_id, category_id, name, created_at) VALUES (?1, ?2, ?3, ?4)",
            params![hid, cat, name, created],
        )?;
        chore_map.insert(*old_id, tx.last_insert_rowid());
    }
    sum.chores = chore_map.len();
    sum.categories = cats.len();

    for h in hist {
        let Some(when) = h.done_at else {
            sum.skipped_bad_date += 1;
            continue;
        };
        let done_on = when.with_timezone(&o.tz).date_naive().to_string();
        tx.execute(
            "INSERT INTO completions (id, chore_id, user_id, done_on, note, created_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![
                uuid_v4()?,
                chore_map[&h.chore],
                who_id[&h.who.to_lowercase()],
                done_on,
                h.note,
                ts(when)
            ],
        )?;
        sum.completions += 1;
    }
    Ok(sum)
}
