' Douyin image extractor - silent launcher
Set WshShell = CreateObject("WScript.Shell")
WshShell.CurrentDirectory = "D:\DouyinExtractor"
WshShell.Run """D:\python\python.exe"" ""D:\DouyinExtractor\douyin_gui.py""", 0, False
