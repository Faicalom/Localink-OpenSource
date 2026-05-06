using LocalBridge.Desktop.Models;

namespace LocalBridge.Desktop.Repositories;

public interface ILocalDeviceProfileRepository
{
    Task<LocalDeviceProfile> LoadOrCreateAsync(CancellationToken cancellationToken = default);

    Task SaveAsync(LocalDeviceProfile profile, CancellationToken cancellationToken = default);
}
