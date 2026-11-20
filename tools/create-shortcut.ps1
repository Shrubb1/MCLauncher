$root = Split-Path -Parent $PSScriptRoot
$pythonw = Join-Path $root ".venv\Scripts\MCLauncher.exe"
$icon = Join-Path $root "assets\icon.ico"
$main = Join-Path $root "main.py"
$lnkPath = Join-Path $env:USERPROFILE "Desktop\MCLauncher.lnk"

$wshell = New-Object -ComObject WScript.Shell
$lnk = $wshell.CreateShortcut($lnkPath)
$lnk.TargetPath = $pythonw
$lnk.Arguments = '"' + $main + '"'
$lnk.WorkingDirectory = $root
$lnk.IconLocation = $icon + ",0"
$lnk.Description = "Minecraft launcher"
$lnk.Save()

Write-Output "Created: $lnkPath"

# Set System.AppUserModel.ID on the shortcut so the running window and
# the pinned taskbar button group together under the same identity.
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;

public static class AppIdSetter {
    [StructLayout(LayoutKind.Sequential)]
    public struct PROPERTYKEY {
        public Guid fmtid;
        public uint pid;
        public PROPERTYKEY(Guid f, uint p) { fmtid = f; pid = p; }
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct PROPVARIANT {
        public ushort vt;
        public ushort wReserved1;
        public ushort wReserved2;
        public ushort wReserved3;
        public IntPtr p;
        public static PROPVARIANT String(string s) {
            PROPVARIANT pv = new PROPVARIANT();
            pv.vt = 31;
            pv.p = Marshal.StringToCoTaskMemUni(s);
            return pv;
        }
    }

    [ComImport, Guid("886d8eeb-8cf2-4446-8d02-cdba1dbdcf99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IPropertyStore {
        int GetCount(out int propCount);
        int GetAt(int propIndex, out PROPERTYKEY pkey);
        int GetValue(ref PROPERTYKEY pkey, out PROPVARIANT pv);
        int SetValue(ref PROPERTYKEY pkey, ref PROPVARIANT pv);
        int Commit();
    }

    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    static extern int SHGetPropertyStoreFromParsingName(
        string pszPath, IntPtr pbc, uint grfMode,
        [MarshalAs(UnmanagedType.LPStruct)] Guid riid,
        out IPropertyStore ppv);

    public static void SetAppId(string lnkPath, string appId) {
        Guid iid = new Guid("886d8eeb-8cf2-4446-8d02-cdba1dbdcf99");
        IPropertyStore store;
        int hr = SHGetPropertyStoreFromParsingName(lnkPath, IntPtr.Zero, 2, iid, out store);
        if (hr != 0) throw new Exception("SHGetPropertyStoreFromParsingName failed: 0x" + hr.ToString("x8"));
        var key = new PROPERTYKEY(new Guid("9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3"), 5);
        var pv = PROPVARIANT.String(appId);
        hr = store.SetValue(ref key, ref pv);
        Marshal.FreeCoTaskMem(pv.p);
        if (hr != 0) throw new Exception("SetValue failed: 0x" + hr.ToString("x8"));
        hr = store.Commit();
        if (hr != 0) throw new Exception("Commit failed: 0x" + hr.ToString("x8"));
    }
}
"@ -Language CSharp

try {
    [AppIdSetter]::SetAppId($lnkPath, "MCLauncher.App")
    Write-Output "AppUserModelID set: MCLauncher.App"
} catch {
    Write-Output "Failed to set AppUserModelID: $_"
}
