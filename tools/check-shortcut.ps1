$w = New-Object -ComObject WScript.Shell
$lnk = $w.CreateShortcut("$env:USERPROFILE\Desktop\MCLauncher.lnk")
Write-Output "TargetPath: $($lnk.TargetPath)"
Write-Output "Arguments: $($lnk.Arguments)"
Write-Output "IconLocation: $($lnk.IconLocation)"
Write-Output "WorkingDirectory: $($lnk.WorkingDirectory)"
