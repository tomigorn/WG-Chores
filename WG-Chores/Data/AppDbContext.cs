using Microsoft.EntityFrameworkCore;

namespace WG_Chores.Data
{
    public class AppDbContext : DbContext
    {
        public AppDbContext(DbContextOptions<AppDbContext> options) : base(options)
        {
        }

        public DbSet<Household> Households { get; set; } = null!;
        public DbSet<Member> Members { get; set; } = null!;
        public DbSet<Chore> Chores { get; set; } = null!;
        public DbSet<ChoreHistory> ChoreHistories { get; set; } = null!;

        protected override void OnModelCreating(ModelBuilder modelBuilder)
        {
            base.OnModelCreating(modelBuilder);

            modelBuilder.Entity<Household>(eb =>
            {
                eb.HasKey(h => h.Id);
                eb.HasIndex(h => h.Code).IsUnique();
                eb.Property(h => h.Name).IsRequired();
                eb.Property(h => h.Code).IsRequired();
                eb.Property(h => h.CreatedAt).HasDefaultValueSql("CURRENT_TIMESTAMP");

                eb.HasMany(h => h.Members)
                    .WithOne(m => m.Household)
                    .HasForeignKey(m => m.HouseholdId)
                    .OnDelete(DeleteBehavior.Cascade);

                eb.HasMany(h => h.Chores)
                    .WithOne(c => c.Household)
                    .HasForeignKey(c => c.HouseholdId)
                    .OnDelete(DeleteBehavior.Cascade);
            });

            modelBuilder.Entity<Member>(eb =>
            {
                eb.HasKey(m => m.Id);
                eb.Property(m => m.Username).IsRequired();
                eb.Property(m => m.CreatedAt).HasDefaultValueSql("CURRENT_TIMESTAMP");
            });

            modelBuilder.Entity<Chore>(eb =>
            {
                eb.HasKey(c => c.Id);
                eb.Property(c => c.Title).IsRequired();
                eb.Property(c => c.IsDone).HasDefaultValue(false);
                eb.Property(c => c.CreatedAt).HasDefaultValueSql("CURRENT_TIMESTAMP");
                eb.Property(c => c.Room).HasMaxLength(64).HasDefaultValue("");
            });

            modelBuilder.Entity<ChoreHistory>(eb =>
            {
                eb.HasKey(h => h.Id);
                eb.Property(h => h.MemberName).HasMaxLength(128).HasDefaultValue("");
                eb.Property(h => h.DoneAt).HasDefaultValueSql("CURRENT_TIMESTAMP");

                eb.HasOne(h => h.Chore)
                    .WithMany()
                    .HasForeignKey(h => h.ChoreId)
                    .OnDelete(DeleteBehavior.Cascade);
            });
        }
    }

    public class Household
    {
        public int Id { get; set; }
        public string Name { get; set; } = null!;
        public string Code { get; set; } = null!;
        public DateTime CreatedAt { get; set; }

        public List<Member> Members { get; set; } = new();
        public List<Chore> Chores { get; set; } = new();
    }

    public class Member
    {
        public int Id { get; set; }
        public string Username { get; set; } = null!;
        public int HouseholdId { get; set; }
        public Household? Household { get; set; }
        public DateTime CreatedAt { get; set; }
    }

    public class Chore
    {
        public int Id { get; set; }
        public string Title { get; set; } = null!;
        public string? Description { get; set; }
        public string? Room { get; set; }
        public bool IsDone { get; set; }
        public int HouseholdId { get; set; }
        public Household? Household { get; set; }
        public DateTime CreatedAt { get; set; }
    }

    public class ChoreHistory
    {
        public int Id { get; set; }
        public int ChoreId { get; set; }
        public Chore? Chore { get; set; }
        // optional member who completed the chore (denormalized name for convenience)
        public int? MemberId { get; set; }
        public string? MemberName { get; set; }
        public DateTime DoneAt { get; set; }
        public string? Notes { get; set; }
    }
}
