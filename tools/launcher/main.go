// mLabeler.exe for the portable Windows build: starts the bundled Java runtime with the app's jars.
package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
	"unsafe"
)

func fail(text string) {
	user32 := syscall.NewLazyDLL("user32.dll")
	box := user32.NewProc("MessageBoxW")
	t, _ := syscall.UTF16PtrFromString(text)
	c, _ := syscall.UTF16PtrFromString("mLabeler")
	box.Call(0, uintptr(unsafe.Pointer(t)), uintptr(unsafe.Pointer(c)), 0x10)
	os.Exit(1)
}

func main() {
	exe, err := os.Executable()
	if err != nil {
		fail(err.Error())
	}
	dir := filepath.Dir(exe)
	java := filepath.Join(dir, "runtime", "bin", "javaw.exe")
	args := []string{
		"-Xss4m",
		"-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8",
		"-cp", filepath.Join(dir, "app", "*"),
		"mlabeler.app.MainKt",
	}
	args = append(args, os.Args[1:]...)
	cmd := exec.Command(java, args...)
	cmd.Dir = dir
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	if err := cmd.Start(); err != nil {
		fail("Can't start Java from " + java + ":\n" + err.Error() + "\n\nUnpack the whole folder, not only mLabeler.exe.")
	}
}
