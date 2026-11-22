' 抖音图文提取器 - Windows 静默启动器
' 把 PYTHON_EXE 改成你本机的 Python 解释器路径，然后双击本文件即可后台启动。
Option Explicit

Const PYTHON_EXE = "D:\python\python.exe"

Dim shell, fso, baseDir, target
Set shell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

baseDir = fso.GetParentFolderName(WScript.ScriptFullName)
target = fso.BuildPath(fso.BuildPath(baseDir, "src"), "douyin_gui.py")

If Not fso.FileExists(target) Then
    MsgBox "未找到 " & target & vbCrLf & "请确认仓库文件完整。", 48, "抖音图文提取器"
    WScript.Quit 1
End If

shell.CurrentDirectory = baseDir
shell.Run """" & PYTHON_EXE & """ """ & target & """", 0, False
