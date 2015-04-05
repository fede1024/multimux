package main

import (
	"fmt"
	"github.com/kr/pty"
	"log"
	"os"
	"os/exec"
	"syscall"
	"unsafe"
)

// PROCESS

type Process struct {
	path          string
	id            int
	tty           *os.File
	command       *exec.Cmd
	stdin, stdout chan []byte
	alive         bool
}

func (proc *Process) ProcessStdinWorker() {
	for input := range proc.stdin {
		nw, err := proc.tty.Write(input)
		if err != nil {
			log.Println(err)
			proc.Terminate()
			break
		}
		if len(input) != nw {
			panic("Fix here")
		}
	}
	fmt.Println("STDIN END")
}

func (proc *Process) ProcessStdoutWorker() {
	for {
		buf := make([]byte, 1024)
		reqLen, err := proc.tty.Read(buf)
		if err != nil {
			log.Println(err)
			proc.Terminate()
			break
		}

		proc.stdout <- buf[:reqLen]
	}
	fmt.Println("STDOUT END")
}

func (proc *Process) Terminate() {
	if proc.alive {
		close(proc.stdin)
		close(proc.stdout)
		proc.alive = false
	}
}

type winsize struct {
	ws_row    uint16
	ws_col    uint16
	ws_xpixel uint16
	ws_ypixel uint16
}

func (proc *Process) setSize(rown, columns, xpixel, ypixel int32) error {
	var ws winsize
	fd := proc.tty.Fd()

	_, _, errno := syscall.Syscall(syscall.SYS_IOCTL, fd, syscall.TIOCGWINSZ, uintptr(unsafe.Pointer(&ws)))
	if errno != 0 {
		return syscall.Errno(errno)
	}

	ws.ws_col = uint16(columns)
	ws.ws_row = uint16(rown)
	ws.ws_xpixel = uint16(xpixel)
	ws.ws_ypixel = uint16(ypixel)

	_, _, errno = syscall.Syscall(syscall.SYS_IOCTL, fd, syscall.TIOCSWINSZ, uintptr(unsafe.Pointer(&ws)))
	if errno != 0 {
		return syscall.Errno(errno)
	}
	return nil
}

// PROCESS REGISTRY

type ProcessRegistry struct {
	processes      []*Process
	newProcessChan chan *Process
}

func NewProcessRegistry() *ProcessRegistry {
	prChan := make(chan *Process)
	return &ProcessRegistry{processes: []*Process{}, newProcessChan: prChan}
}

func (pr *ProcessRegistry) AddProcess(proc *Process) {
	proc.id = len(pr.processes)
	pr.processes = append(pr.processes, proc)
	select {
	case pr.newProcessChan <- proc: // Notify the new process
	default: // Do nothing if full
	}
}

func NewProcess(path string) (*Process, error) {
	c := exec.Command(path)
	f, err := pty.Start(c)
	if err != nil {
		return nil, err
	}

	p := &Process{path: path, id: -1, tty: f, command: c, alive: true}
	p.stdin = make(chan []byte)
	p.stdout = make(chan []byte)

	go p.ProcessStdinWorker()
	go p.ProcessStdoutWorker()

	return p, nil
}

func (pr *ProcessRegistry) GetProcess(id int) *Process {
	return pr.processes[id]
}
