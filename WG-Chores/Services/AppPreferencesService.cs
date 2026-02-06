namespace WG_Chores.Services;

public class AppPreferencesService
{
    public bool ShowMemberNames { get; private set; }

    public event Action? OnShowMemberNamesChanged;

    public void SetShowMemberNames(bool value)
    {
        if (ShowMemberNames == value) return;
        ShowMemberNames = value;
        OnShowMemberNamesChanged?.Invoke();
    }
}
