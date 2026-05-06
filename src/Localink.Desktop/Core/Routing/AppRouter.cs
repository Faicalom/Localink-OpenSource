using Localink.Desktop.Core.Mvvm;

namespace Localink.Desktop.Core.Routing;

public sealed class AppRouter : ObservableObject
{
    private string _currentRoute = AppRoutes.Home;

    public string CurrentRoute
    {
        get => _currentRoute;
        private set => SetProperty(ref _currentRoute, value);
    }

    public void Navigate(string route)
    {
        CurrentRoute = route;
    }
}
