using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;

namespace QueueBox.Inbox.DependencyInjection;

/// <summary>Registers a named QueueBox inbox worker as a hosted service.</summary>
public static class ServiceCollectionExtensions
{
    /// <summary>
    /// Add one named worker. Each call registers its own <see cref="InboxWorkerHostedService"/>
    /// carrying the given <see cref="InboxOptions"/> instance, so a host with several workers
    /// keeps every one of them running.
    /// </summary>
    /// <param name="services">The container.</param>
    /// <param name="name">A name that is unique among the workers of this container.</param>
    /// <param name="options">The worker's own options. No other worker must share this instance.</param>
    /// <param name="handler">Handles one message.</param>
    /// <param name="connections">
    /// The connection source for this worker only. When null, the worker resolves
    /// <see cref="IInboxConnectionSource"/> from the container instead, so every worker that
    /// omits this parameter shares one database. Pass a distinct instance for a worker whose
    /// source lives in a different database than the rest of the host.
    /// </param>
    /// <returns>The same container, for chaining.</returns>
    /// <exception cref="InvalidOperationException">The name repeats an earlier registration.</exception>
    public static IServiceCollection AddQueueBoxInbox(
        this IServiceCollection services,
        string name,
        InboxOptions options,
        InboxHandler handler,
        IInboxConnectionSource? connections = null)
    {
        ArgumentNullException.ThrowIfNull(handler);

        return services.AddQueueBoxInbox(name, options, _ => handler, connections);
    }

    /// <summary>
    /// Add one named worker whose handler comes from the container. Use this overload when the
    /// handler needs services that exist only after the host builds the provider.
    /// </summary>
    /// <param name="services">The container.</param>
    /// <param name="name">A name that is unique among the workers of this container.</param>
    /// <param name="options">The worker's own options. No other worker must share this instance.</param>
    /// <param name="handlerFactory">
    /// Builds the handler. It runs once, when the host creates the worker, and it receives the
    /// root provider. A scope for each message is the business of the handler, not of the worker.
    /// </param>
    /// <param name="connections">
    /// The connection source for this worker only. When null, the worker resolves
    /// <see cref="IInboxConnectionSource"/> from the container instead.
    /// </param>
    /// <returns>The same container, for chaining.</returns>
    /// <exception cref="InvalidOperationException">
    /// The name repeats an earlier registration, or the factory returns null when the host
    /// creates the worker.
    /// </exception>
    public static IServiceCollection AddQueueBoxInbox(
        this IServiceCollection services,
        string name,
        InboxOptions options,
        Func<IServiceProvider, InboxHandler> handlerFactory,
        IInboxConnectionSource? connections = null)
    {
        ArgumentNullException.ThrowIfNull(services);
        ArgumentException.ThrowIfNullOrWhiteSpace(name);
        ArgumentNullException.ThrowIfNull(options);
        ArgumentNullException.ThrowIfNull(handlerFactory);

        var names = GetRegisteredNames(services);
        if (!names.Add(name))
        {
            throw new InvalidOperationException(
                $"A worker named '{name}' is already registered. Each worker needs a unique name.");
        }

        options.Validate();

        // The generic AddHostedService<T>() registers by implementation type. A second call with
        // the same implementation type is dropped, so a second named worker disappears in
        // silence. AddSingleton<IHostedService>(...) keeps every registration, because each one
        // carries its own factory and its own InboxWorkerHostedService instance.
        services.AddSingleton<IHostedService>(provider =>
        {
            var resolvedConnections = connections ?? provider.GetRequiredService<IInboxConnectionSource>();
            var logger = provider.GetService<Microsoft.Extensions.Logging.ILogger<InboxWorker>>();
            var timeProvider = provider.GetService<TimeProvider>();
            var handler = handlerFactory(provider)
                ?? throw new InvalidOperationException(
                    $"The handler factory of the worker '{name}' returned null.");
            return new InboxWorkerHostedService(resolvedConnections, options, handler, logger, timeProvider);
        });

        return services;
    }

    private static HashSet<string> GetRegisteredNames(IServiceCollection services)
    {
        foreach (var descriptor in services)
        {
            if (descriptor.ServiceType == typeof(RegisteredWorkerNames)
                && descriptor.ImplementationInstance is RegisteredWorkerNames marker)
            {
                return marker.Names;
            }
        }

        var created = new RegisteredWorkerNames();
        services.AddSingleton(created);
        return created.Names;
    }

    /// <summary>
    /// Tracks the worker names already registered in one container, so a duplicate name is caught
    /// at registration time rather than at run time.
    /// </summary>
    private sealed class RegisteredWorkerNames
    {
        public HashSet<string> Names { get; } = new(StringComparer.Ordinal);
    }
}
