package main

import (
	"github.com/kr/pty"
	"log"
	"os"
	"os/exec"
	"syscall"
	"time"
	"unsafe"
)

func openTty(c *exec.Cmd) (ptty *os.File, err error) {
	ptty, tty, err := pty.Open()
	if err != nil {
		return nil, err
	}
	//defer tty.Close()
	c.Stdout = tty
	c.Stdin = tty
	c.Stderr = tty
	c.SysProcAttr = &syscall.SysProcAttr{Setctty: true, Setsid: true}

	return ptty, err
}

// PROCESS

type Process struct {
	path          string
	id            int
	tty           *os.File
	command       *exec.Cmd
	stdin, stdout chan []byte
	terminate     chan bool
	alive         bool
}

func NewProcess(path string) (*Process, error) {
	c := exec.Command(path)
	tty, err := openTty(c)
	if err != nil {
		return nil, err
	}

	return &Process{path: path, id: -1, tty: tty, command: c, alive: true}, nil
}

func (proc *Process) Start() error {
	err := proc.command.Start()
	if err != nil {
		return err
	}

	proc.stdin = make(chan []byte)
	proc.stdout = make(chan []byte)
	proc.terminate = make(chan bool)

	go proc.StdinWorker()
	go proc.StdoutWorker()
	go proc.TerminationWorker()

	proc.alive = true

	return nil
}

func (proc *Process) StdinWorker() {
	for input := range proc.stdin {
		nw, err := proc.tty.Write(input)
		if err != nil {
			log.Println(err)
			select {
			case proc.terminate <- true:
			default:
			}
			break
		}
		if len(input) != nw {
			panic("Fix here")
		}
	}
	log.Println("STDIN END")
}

func (proc *Process) StdoutWorker() {
	for {
		buf := make([]byte, 1024)
		reqLen, err := proc.tty.Read(buf)
		if err != nil {
			log.Println(err)
			select {
			case proc.terminate <- true:
			default:
			}
			break
		}

		proc.stdout <- buf[:reqLen]
	}
	log.Println("STDOUT END")
}

func (proc *Process) TerminationWorker() {
	done := make(chan error, 1)
	go func() {
		done <- proc.command.Wait()
	}()

	select {
	case <-proc.terminate:
		waitAndKill(proc.command)
	case <-done:
		// Process already terminated
	}

	proc.alive = false
	close(proc.stdin)
	close(proc.stdout)
	close(proc.terminate)
	proc.tty.Close()
	log.Println("Process teminated")
}

func waitAndKill(cmd *exec.Cmd) {
	done := make(chan error, 1)
	go func() {
		done <- cmd.Wait()
	}()
	select {
	case <-time.After(3 * time.Second):
		if err := cmd.Process.Kill(); err != nil {
			log.Fatal("Failed to kill: ", err)
		}
		<-done // allow goroutine to exit
		log.Println("Process killed")
	case err := <-done:
		if err != nil {
			log.Printf("process done with error = %v", err)
		}
	}
}

func (proc *Process) SetSize(row, columns, xpixel, ypixel int32) error {
	return setTtySize(proc.tty.Fd(), row, columns, xpixel, ypixel)
}

type winsize struct {
	ws_row    uint16
	ws_col    uint16
	ws_xpixel uint16
	ws_ypixel uint16
}

func setTtySize(fd uintptr, rows, columns, xpixel, ypixel int32) error {
	var ws winsize

	_, _, errno := syscall.Syscall(syscall.SYS_IOCTL, fd, syscall.TIOCGWINSZ, uintptr(unsafe.Pointer(&ws)))
	if errno != 0 {
		return syscall.Errno(errno)
	}

	ws.ws_col = uint16(columns)
	ws.ws_row = uint16(rows)
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

func (pr *ProcessRegistry) GetProcess(id int) *Process {
	if id >= 0 && id < len(pr.processes) {
		return pr.processes[id]
	}
	return nil
}
