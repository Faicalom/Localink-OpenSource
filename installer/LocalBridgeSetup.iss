#define MyAppName "Localink"
#define MyAppVersion "1.0.0"
#define MyAppPublisher "Localink Project"
#define MyAppExeName "Localink.exe"

[Setup]
AppId={{E4F7A95E-0EE1-4D58-B1CE-BD87B7D05C80}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppVerName={#MyAppName} {#MyAppVersion}
DefaultDirName={commonpf32}\Localink
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
PrivilegesRequired=admin
OutputDir=output
OutputBaseFilename=Localink-Setup
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
SetupIconFile=..\src\LocalBridge.Desktop\Assets\AppIcon.ico
UninstallDisplayIcon={app}\{#MyAppExeName}
ArchitecturesAllowed=x64compatible

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional icons:"; Flags: checkedonce

[Files]
Source: "publish\win-x64\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autodesktop}\Localink"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon
Name: "{group}\Localink"; Filename: "{app}\{#MyAppExeName}"
Name: "{group}\Uninstall Localink"; Filename: "{uninstallexe}"

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "Launch Localink"; Flags: nowait postinstall skipifsilent

[Code]
function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  WizardForm.StatusLabel.Caption :=
    'Preparing the self-contained Localink setup package.';
  Result := '';
end;
