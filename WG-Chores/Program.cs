using WG_Chores.Components;
using Microsoft.EntityFrameworkCore;
using WG_Chores.Data;

var builder = WebApplication.CreateBuilder(args);

// Add console logging explicitly
builder.Logging.ClearProviders();
builder.Logging.AddConsole();

// Add services to the container.
builder.Services.AddRazorComponents()
    .AddInteractiveServerComponents();

// Configure EF Core with SQLite
var connectionString = builder.Configuration.GetConnectionString("Default") ?? "Data Source=wgchores.db";
builder.Services.AddDbContext<AppDbContext>(options => options.UseSqlite(connectionString));

// Register a simple households service
builder.Services.AddScoped<IHouseholdService, HouseholdService>();

var app = builder.Build();

// Ensure database created (development convenience)
using (var scope = app.Services.CreateScope())
{
    var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
    db.Database.EnsureCreated();
}

// Configure the HTTP request pipeline.
if (app.Environment.IsDevelopment())
{
    app.UseDeveloperExceptionPage();
}
else
{
    app.UseExceptionHandler("/Error", createScopeForErrors: true);
    // The default HSTS value is 30 days. You may want to change this for production scenarios, see https://aka.ms/aspnetcore-hsts.
    app.UseHsts();
}

app.UseHttpsRedirection();

app.UseStaticFiles();
app.UseAntiforgery();

app.MapRazorComponents<App>()
    .AddInteractiveServerRenderMode();

app.Run();

// Simple households service implementation
public interface IHouseholdService
{
    Task<Household> CreateAsync(string name, string ownerUsername, CancellationToken cancellationToken = default);
    Task<Household?> GetByCodeAsync(string code, CancellationToken cancellationToken = default);
    Task<Member?> JoinAsync(string code, string username, CancellationToken cancellationToken = default);

    Task<Household?> GetByIdAsync(int householdId, CancellationToken cancellationToken = default);
    Task<List<Household>> GetByUsernameAsync(string username, CancellationToken cancellationToken = default);

    Task<Chore> AddChoreAsync(int householdId, string title, string? description = null, string? room = null, CancellationToken cancellationToken = default);
    Task<bool> RemoveChoreAsync(int choreId, CancellationToken cancellationToken = default);
    Task<Chore?> UpdateChoreAsync(Chore chore, CancellationToken cancellationToken = default);
    Task<ChoreHistory> AddChoreHistoryAsync(int choreId, int? memberId, string? memberName, string? notes = null, DateTime? doneAt = null, CancellationToken cancellationToken = default);
    Task<List<ChoreHistory>> GetChoreHistoryAsync(int choreId, CancellationToken cancellationToken = default);
}

public class HouseholdService : IHouseholdService
{
    private readonly AppDbContext _db;

    public HouseholdService(AppDbContext db)
    {
        _db = db;
    }

    public async Task<Household> CreateAsync(string name, string ownerUsername, CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(name)) throw new ArgumentException("name is required", nameof(name));
        if (string.IsNullOrWhiteSpace(ownerUsername)) throw new ArgumentException("ownerUsername is required", nameof(ownerUsername));

        name = name.Trim();
        ownerUsername = ownerUsername.Trim();

        const int maxSaveAttempts = 5;

        for (int attempt = 0; attempt < maxSaveAttempts; attempt++)
        {
            var household = new Household
            {
                Name = name,
                Code = await GenerateUniqueCodeAsync(cancellationToken),
                CreatedAt = DateTime.UtcNow
            };

            var owner = new Member
            {
                Username = ownerUsername,
                Household = household,
                CreatedAt = DateTime.UtcNow
            };

            _db.Households.Add(household);
            _db.Members.Add(owner);

            try
            {
                await _db.SaveChangesAsync(cancellationToken);
                return household;
            }
            catch (DbUpdateException dbEx)
            {
                // Likely a unique constraint collision on Code; log and retry
                Console.Error.WriteLine($"CreateAsync SaveChanges attempt {attempt} failed: {dbEx}");

                // Detach added entities so we can retry cleanly
                var entries = _db.ChangeTracker.Entries().ToList();
                foreach (var e in entries)
                {
                    e.State = EntityState.Detached;
                }

                // If last attempt, rethrow
                if (attempt == maxSaveAttempts - 1)
                    throw;

                // otherwise try again with a new code
                await Task.Delay(50, cancellationToken);
                continue;
            }
        }

        // Should not get here
        throw new InvalidOperationException("Failed to create household after multiple attempts");
    }

    public async Task<Household?> GetByCodeAsync(string code, CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(code)) return null;
        code = code.Trim();
        return await _db.Households.FirstOrDefaultAsync(h => h.Code == code, cancellationToken);
    }

    public async Task<Member?> JoinAsync(string code, string username, CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(code) || string.IsNullOrWhiteSpace(username))
        {
            return null;
        }

        code = code.Trim();
        username = username.Trim();

        try
        {
            var household = await GetByCodeAsync(code, cancellationToken);
            if (household == null)
                return null;

            // Check membership directly in the database to avoid relying on unloaded navigation properties
            var existing = await _db.Members.FirstOrDefaultAsync(m => m.HouseholdId == household.Id && m.Username.ToLower() == username.ToLower(), cancellationToken);
            if (existing != null)
                return existing;

            var member = new Member
            {
                Username = username,
                HouseholdId = household.Id,
                CreatedAt = DateTime.UtcNow
            };

            _db.Members.Add(member);
            await _db.SaveChangesAsync(cancellationToken);
            return member;
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"JoinAsync failed for code={code}, username={username}: {ex}");
            return null;
        }
    }

    public async Task<Household?> GetByIdAsync(int householdId, CancellationToken cancellationToken = default)
    {
        return await _db.Households.Include(h => h.Members).Include(h => h.Chores).FirstOrDefaultAsync(h => h.Id == householdId, cancellationToken);
    }

    public async Task<List<Household>> GetByUsernameAsync(string username, CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(username)) return new List<Household>();
        username = username.Trim();

        return await _db.Households
            .Include(h => h.Members)
            .Where(h => h.Members.Any(m => m.Username.ToLower() == username.ToLower()))
            .ToListAsync(cancellationToken);
    }

    public async Task<Chore> AddChoreAsync(int householdId, string title, string? description = null, string? room = null, CancellationToken cancellationToken = default)
    {
        var chore = new Chore
        {
            Title = title,
            Description = description,
            Room = room,
            HouseholdId = householdId,
            CreatedAt = DateTime.UtcNow,
            IsDone = false
        };
        _db.Chores.Add(chore);
        await _db.SaveChangesAsync(cancellationToken);
        return chore;
    }

    public async Task<bool> RemoveChoreAsync(int choreId, CancellationToken cancellationToken = default)
    {
        var chore = await _db.Chores.FindAsync(new object[] { choreId }, cancellationToken);
        if (chore == null) return false;
        _db.Chores.Remove(chore);
        await _db.SaveChangesAsync(cancellationToken);
        return true;
    }

    public async Task<Chore?> UpdateChoreAsync(Chore chore, CancellationToken cancellationToken = default)
    {
        var existing = await _db.Chores.FindAsync(new object[] { chore.Id }, cancellationToken);
        if (existing == null) return null;

        var wasDone = existing.IsDone;

        existing.Title = chore.Title;
        existing.Description = chore.Description;
        existing.Room = chore.Room;
        existing.IsDone = chore.IsDone;

        await _db.SaveChangesAsync(cancellationToken);

        // if chore transitioned from not done -> done, record a history entry (no member info)
        if (!wasDone && existing.IsDone)
        {
            var history = new ChoreHistory
            {
                ChoreId = existing.Id,
                MemberId = null,
                MemberName = null,
                Notes = null,
                DoneAt = DateTime.UtcNow
            };
            _db.ChoreHistories.Add(history);
            await _db.SaveChangesAsync(cancellationToken);
        }

        return existing;
    }

    public async Task<ChoreHistory> AddChoreHistoryAsync(int choreId, int? memberId, string? memberName, string? notes = null, DateTime? doneAt = null, CancellationToken cancellationToken = default)
    {
        var history = new ChoreHistory
        {
            ChoreId = choreId,
            MemberId = memberId,
            MemberName = memberName,
            Notes = notes,
            DoneAt = doneAt ?? DateTime.UtcNow
        };
        _db.ChoreHistories.Add(history);
        await _db.SaveChangesAsync(cancellationToken);
        return history;
    }

    public async Task<List<ChoreHistory>> GetChoreHistoryAsync(int choreId, CancellationToken cancellationToken = default)
    {
        return await _db.ChoreHistories.Where(h => h.ChoreId == choreId).OrderByDescending(h => h.DoneAt).ToListAsync(cancellationToken);
    }

    private async Task<string> GenerateUniqueCodeAsync(CancellationToken cancellationToken)
    {
        const int maxAttempts = 10;
        for (int attempt = 0; attempt < maxAttempts; attempt++)
        {
            var code = GenerateCode();
            var exists = await _db.Households.AnyAsync(h => h.Code == code, cancellationToken);
            if (!exists)
                return code;
        }

        // Fallback: GUID-based code
        return Guid.NewGuid().ToString("N").Substring(0, 8).ToUpperInvariant();
    }

    private static string GenerateCode()
    {
        // Simple 6-character base36 code
        const string chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        return new string(Enumerable.Range(0, 6).Select(_ => chars[Random.Shared.Next(chars.Length)]).ToArray());
    }
}
