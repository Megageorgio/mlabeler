@echo off
rem Starts mLabeler on Windows 7 SP1 and 8.1, where mLabeler.exe does not run (experimental, without the toolkit).
setlocal
set "D=%~dp0"
set "D=%D:~0,-1%"
set EXTRA=
if not exist "%D%\portable" goto run
set "MLABELER_HOME=%D%\data"
set "MVT_HOME=%D%\toolkit"
set "TEMP=%D%\data\tmp"
set "TMP=%D%\data\tmp"
if not exist "%D%\data\tmp" mkdir "%D%\data\tmp"
set EXTRA=-XX:-UsePerfData "-Dmlabeler.portable=%D%" "-Djava.io.tmpdir=%D%\data\tmp"
:run
start "" "%D%\runtime\bin\javaw.exe" %EXTRA% -Xss4m -Dfile.encoding=UTF-8 "-Dskiko.library.path=%D%\natives" "-Dorg.lwjgl.librarypath=%D%\natives" -cp "%D%\app\*" mlabeler.app.MainKt %*
