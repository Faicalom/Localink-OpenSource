using Localink.Desktop.Models;

namespace Localink.Desktop.Repositories;

public interface ILocalDeviceProfileRepository
{
    Task<LocalDeviceProfile> LoadOrCreateAsync(CancellationToken cancellationToken = default);

    Task SaveAsync(LocalDeviceProfile profile, CancellationToken cancellationToken = default);
}

