// mLabeler.exe for the portable Windows build: starts the bundled Java runtime with the app's jars.
// What Java prints goes to launch.log in the program's data folder; if the program stops right after the start,
// the end of that log is shown in a message box, so a failed start never looks like "nothing happens".
package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

func message(text string) {
	user32 := syscall.NewLazyDLL("user32.dll")
	box := user32.NewProc("MessageBoxW")
	t, _ := syscall.UTF16PtrFromString(text)
	c, _ := syscall.UTF16PtrFromString("mLabeler")
	box.Call(0, uintptr(unsafe.Pointer(t)), uintptr(unsafe.Pointer(c)), 0x10)
}

func fail(text string) {
	message(text)
	os.Exit(1)
}

// The same folder the program keeps its settings in (MLABELER_HOME, or %APPDATA%\mLabeler).
func dataDir() string {
	if d := os.Getenv("MLABELER_HOME"); d != "" {
		return d
	}
	if d := os.Getenv("APPDATA"); d != "" {
		return filepath.Join(d, "mLabeler")
	}
	home, _ := os.UserHomeDir()
	return filepath.Join(home, "mLabeler")
}

// The last lines of the log, for the message box.
func tail(path string, lines int) string {
	b, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	all := strings.Split(strings.TrimRight(strings.ReplaceAll(string(b), "\r\n", "\n"), "\n"), "\n")
	if len(all) > lines {
		all = all[len(all)-lines:]
	}
	return strings.Join(all, "\n")
}

func main() {
	exe, err := os.Executable()
	if err != nil {
		fail(err.Error())
	}
	dir := filepath.Dir(exe)
	// java.exe (a console program) started without a window: the app and everything it starts (the toolkit,
	// its engines, ffmpeg) share one hidden console, so no console windows pop up.
	java := filepath.Join(dir, "runtime", "bin", "java.exe")
	if _, err := os.Stat(java); err != nil {
		fail("Java was not found at " + java + ".\n\nUnpack the whole folder, not only mLabeler.exe.")
	}
	args := []string{
		"-Xss4m",
		// no -Dsun.jnu.encoding: forced to UTF-8 it garbles a Cyrillic (or any non-Latin) user name in the
		// paths Java gets from Windows, and the graphics library cannot unpack itself into the home folder
		"-Dfile.encoding=UTF-8",
		"-cp", filepath.Join(dir, "app", "*"),
		"mlabeler.app.MainKt",
	}
	args = append(args, os.Args[1:]...)
	cmd := exec.Command(java, args...)
	cmd.Dir = dir
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000} // CREATE_NO_WINDOW

	logPath := filepath.Join(dataDir(), "launch.log")
	var log *os.File
	if os.MkdirAll(filepath.Dir(logPath), 0o755) == nil {
		log, _ = os.Create(logPath)
	}
	if log != nil {
		fmt.Fprintf(log, "mLabeler started %s\nfolder: %s\njava: %s\n\n", time.Now().Format("2006-01-02 15:04:05"), dir, java)
		cmd.Stdout = log
		cmd.Stderr = log
	}
	if err := cmd.Start(); err != nil {
		fail("Can't start Java from " + java + ":\n" + err.Error() + "\n\nUnpack the whole folder, not only mLabeler.exe.")
	}
	// a program that stops within the first seconds did not start: say why
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	select {
	case err := <-done:
		if err != nil {
			if log != nil {
				log.Sync()
			}
			text := "mLabeler could not start (" + err.Error() + ").\n\n"
			if t := tail(logPath, 25); t != "" {
				text += t + "\n\n"
			}
			text += "The whole report is in " + logPath + ". Please send it to the author."
			fail(text)
		}
	case <-time.After(20 * time.Second):
		// running: the launcher steps aside, the log stays open for the program
	}
}
