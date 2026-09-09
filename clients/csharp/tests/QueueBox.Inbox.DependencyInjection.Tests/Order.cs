using Microsoft.EntityFrameworkCore;

namespace QueueBox.Inbox.DependencyInjection.Tests;

/// <summary>One application row, written through <see cref="InboxDbContextFactory"/> in a test handler.</summary>
public sealed class Order
{
    /// <summary>The row identity. The test sets it to the id of the inbox message.</summary>
    public Guid Id { get; set; }
}

/// <summary>The application context a test handler builds on the message transaction.</summary>
public sealed class OrderContext(DbContextOptions<OrderContext> options) : DbContext(options)
{
    /// <summary>The application table the rollback test proves against.</summary>
    public DbSet<Order> Orders => Set<Order>();

    /// <inheritdoc />
    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        ArgumentNullException.ThrowIfNull(modelBuilder);

        modelBuilder.Entity<Order>(entity =>
        {
            entity.ToTable("ef_orders");
            entity.HasKey(order => order.Id);
        });
    }
}
