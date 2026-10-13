package main

// Installing a downloaded update. The program unpacks the new version into <data>\update\<tag> and writes the tag
// into <data>\update\ready; when it closes it starts "mLabeler.exe --apply-update <pid> <update folder> <0|1>".
// This waits until that process is gone, then puts the new folders in place of the old ones: the new folders are
// copied next to the old ones first, then swapped by renaming, so a failed copy leaves the old version working and
// a swap that fails half way puts the old folders back. The settings, data, toolkit and the "portable" marker are
// never touched. (It used to be a hidden PowerShell script, which antivirus heuristics took for a trojan.)

import (
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

var parts = []string{"app", "runtime", "natives"}

func applyUpdate(dir string, args []string) {
	if len(args) < 3 {
		return
	}
	pid, _ := strconv.Atoi(args[0])
	updates := args[1]
	restart := args[2] == "1"
	logf := func(format string, a ...any) {
		f, err := os.OpenFile(filepath.Join(updates, "update.log"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
		if err != nil {
			return
		}
		defer f.Close()
		fmt.Fprintf(f, "%s %s\n", time.Now().Format("2006-01-02T15:04:05"), fmt.Sprintf(format, a...))
	}
	// the program has to be gone (its files are in use while it runs)
	if pid > 0 {
		if p, err := os.FindProcess(pid); err == nil {
			waited := make(chan struct{})
			go func() { p.Wait(); close(waited) }()
			select {
			case <-waited:
			case <-time.After(3 * time.Minute):
				logf("update not installed: the program did not close")
				return
			}
		}
		time.Sleep(700 * time.Millisecond)
	}
	readyFile := filepath.Join(updates, "ready")
	b, err := os.ReadFile(readyFile)
	tag := strings.TrimSpace(string(b))
	if err != nil || tag == "" {
		logf("nothing to install")
		return
	}
	src := filepath.Join(updates, tag, "mLabeler")
	if _, err := os.Stat(filepath.Join(src, "mLabeler.exe")); err != nil {
		src = filepath.Join(updates, tag)
	}
	if err := swap(src, dir); err != nil {
		logf("update failed: %v", err)
	} else {
		logf("updated to %s", tag)
		os.Remove(readyFile)
		os.RemoveAll(filepath.Join(updates, tag))
	}
	if restart {
		cmd := exec.Command(filepath.Join(dir, "mLabeler.exe"))
		cmd.Dir = dir
		cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: false}
		cmd.Start()
	}
}

// swap puts the folders and files of src in place of those in dst, keeping the old ones until all is done.
func swap(src, dst string) (err error) {
	var present []string
	for _, d := range parts {
		if st, e := os.Stat(filepath.Join(src, d)); e == nil && st.IsDir() {
			present = append(present, d)
		}
	}
	// 1. copies next to the old folders
	for _, d := range present {
		t := filepath.Join(dst, d+".new")
		os.RemoveAll(t)
		if e := copyDir(filepath.Join(src, d), t); e != nil {
			for _, x := range present {
				os.RemoveAll(filepath.Join(dst, x+".new"))
			}
			return fmt.Errorf("copying %s: %w", d, e)
		}
	}
	// 2. the swap; on failure everything swapped so far goes back
	swapped := []string{}
	defer func() {
		if err == nil {
			for _, d := range parts {
				if _, e := os.Stat(filepath.Join(dst, d)); e == nil {
					os.RemoveAll(filepath.Join(dst, d+".old"))
				}
			}
			return
		}
		for _, d := range swapped {
			cur, old := filepath.Join(dst, d), filepath.Join(dst, d+".old")
			if _, e := os.Stat(old); e == nil {
				os.RemoveAll(cur)
				os.Rename(old, cur)
			}
		}
		for _, d := range present {
			os.RemoveAll(filepath.Join(dst, d+".new"))
		}
	}()
	for _, d := range present {
		cur, old, nw := filepath.Join(dst, d), filepath.Join(dst, d+".old"), filepath.Join(dst, d+".new")
		os.RemoveAll(old)
		if _, e := os.Stat(cur); e == nil {
			if e := retry(func() error { return os.Rename(cur, old) }); e != nil {
				return fmt.Errorf("moving the old %s aside: %w", d, e)
			}
		}
		swapped = append(swapped, d)
		if e := os.Rename(nw, cur); e != nil {
			return fmt.Errorf("putting the new %s in place: %w", d, e)
		}
	}
	// 3. the files of the program folder (mLabeler.exe and the others); "portable" stays as it is
	entries, e := os.ReadDir(src)
	if e != nil {
		return e
	}
	for _, en := range entries {
		if en.IsDir() || en.Name() == "portable" {
			continue
		}
		to := filepath.Join(dst, en.Name())
		if strings.EqualFold(en.Name(), "mLabeler.exe") {
			// this very program: a running .exe can be renamed, not overwritten
			os.Remove(to + ".old")
			os.Rename(to, to+".old")
		}
		if e := copyFile(filepath.Join(src, en.Name()), to); e != nil {
			return fmt.Errorf("copying %s: %w", en.Name(), e)
		}
	}
	return nil
}

func retry(f func() error) (err error) {
	for k := 0; k < 40; k++ {
		if err = f(); err == nil {
			return nil
		}
		time.Sleep(500 * time.Millisecond)
	}
	return err
}

func copyDir(src, dst string) error {
	return filepath.Walk(src, func(p string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		rel, _ := filepath.Rel(src, p)
		to := filepath.Join(dst, rel)
		if info.IsDir() {
			return os.MkdirAll(to, 0o755)
		}
		return copyFile(p, to)
	})
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	os.MkdirAll(filepath.Dir(dst), 0o755)
	out, err := os.Create(dst)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return err
	}
	return out.Close()
}

// The old launcher left by an update (it was running while it was replaced) goes on the next start.
func cleanOldExe(dir string) {
	os.Remove(filepath.Join(dir, "mLabeler.exe.old"))
}
