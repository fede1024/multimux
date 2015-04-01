package main

import (
	"fmt"
	"github.com/fede1024/goavro"
	"github.com/kr/pty"
	"io"
	"log"
	"net"
	"os"
	"os/exec"
	"syscall"
	"unsafe"
)

var inputOutputSchema goavro.RecordSetter
var inputOutputCodec goavro.Codec
var resizeSchema goavro.RecordSetter
var resizeCodec goavro.Codec
var messageSchema goavro.RecordSetter
var messageCodec goavro.Codec

const (
	CONN_HOST = "localhost"
	CONN_PORT = "3333"
	CONN_TYPE = "tcp"
)

func inputMessagesWorker(receiveChan <-chan *goavro.Record, registry *processRegistry) {
	for msg := range receiveChan {
		fmt.Println("RCV", msg)
		messageType, err := msg.Get("messageType")
		if err != nil {
			fmt.Println(err)
			continue
		}
		payload, err := msg.Get("data")
		if err != nil {
			fmt.Println(err)
			continue
		}
		dataRecord := payload.(*goavro.Record)
		if messageType == "input" {
			bytesRaw, _ := dataRecord.Get("bytes")
			registry.GetProcess(0).stdin <- bytesRaw.([]byte)
		} else if messageType == "resize" {
			cols, _ := dataRecord.Get("cols")
			rows, _ := dataRecord.Get("rows")
			xpixel, _ := dataRecord.Get("xpixel")
			ypixel, _ := dataRecord.Get("ypixel")

			setSize(registry.GetProcess(0).tty.Fd(), cols.(int32), rows.(int32), xpixel.(int32), ypixel.(int32))
		}
	}
}

func outputMessagesWorker(sendChan chan<- *goavro.Record, registry *processRegistry) {
	fmt.Println(">>", registry.GetProcess(0).stdout)
	for bytes := range registry.GetProcess(0).stdout {
		fmt.Println("SND", bytes)
		sendChan <- makeOutputMessage(bytes)
	}
}

func (proc process) ProcessStdinWorker() {
	for input := range proc.stdin {
		fmt.Println("STDIN", input)
		nw, err := proc.tty.Write(input)
		if err != nil {
			log.Fatal(err)
		}
		if len(input) != nw {
			panic("Fix here")
		}
	}
	fmt.Println("STDIN END")
}

func (proc process) ProcessStdoutWorker() {
	fmt.Println(">>", proc.stdout)
	for {
		buf := make([]byte, 1024)
		reqLen, err := proc.tty.Read(buf)
		if err != nil {
			log.Fatal(err)
		}

		fmt.Println("STDOUT", buf[:reqLen])
		proc.stdout <- buf[:reqLen]
		fmt.Println("STDOUT DOPO")
	}
	fmt.Println("STDOUT END")
}

func makeOutputMessage(data []byte) *goavro.Record {
	record, err := goavro.NewRecord(messageSchema)
	if err != nil {
		log.Fatal(err)
	}

	inputOutput, err := goavro.NewRecord(inputOutputSchema)
	if err != nil {
		log.Fatal(err)
	}
	inputOutput.Set("bytes", data)
	record.Set("messageType", "output")
	record.Set("data", inputOutput)

	return record
}

type process struct {
	path          string
	id            int
	tty           *os.File
	command       *exec.Cmd
	stdin, stdout chan []byte
}

type processRegistry struct {
	newId     int
	processes map[int]*process
}

func NewProcessRegistry() *processRegistry {
	return &processRegistry{processes: make(map[int]*process)}
}

func (pr processRegistry) NewProcess(path string) (*process, error) {
	c := exec.Command(path)
	f, err := pty.Start(c)
	if err != nil {
		return nil, err
	}

	stdinChan := make(chan []byte)
	stdoutChan := make(chan []byte)

	p := &process{path: path, id: pr.newId, tty: f, command: c, stdin: stdinChan, stdout: stdoutChan}
	pr.newId++
	pr.processes[p.id] = p

	return p, nil
}

func (pr processRegistry) GetProcess(id int) *process {
	return pr.processes[id]
}

func main() {

	registry := NewProcessRegistry()
	proc, err := registry.NewProcess("/bin/bash")
	if err != nil {
		fmt.Println("Error while starting process:", err)
		os.Exit(1)
	}
	go proc.ProcessStdinWorker()
	go proc.ProcessStdoutWorker()

	goavro.NewSymtab()

	receiveChan := make(chan *goavro.Record)
	sendChan := make(chan *goavro.Record)

	st := goavro.NewSymtab()

	inputOutputCodec, inputOutputSchema, err = LoadCodec(st, "../../../avro/InputOutput.avsc")
	resizeCodec, resizeSchema, err = LoadCodec(st, "../../../avro/Resize.avsc")
	messageCodec, messageSchema, err = LoadCodec(st, "../../../avro/Message.avsc")
	if err != nil {
		fmt.Println("Can't create message codec:", err)
		return
	}

	// Listen for incoming connections.
	l, err := net.Listen(CONN_TYPE, CONN_HOST+":"+CONN_PORT)
	if err != nil {
		fmt.Println("Error listening:", err.Error())
		os.Exit(1)
	}
	// Close the listener when the application closes.
	defer l.Close()

	go inputMessagesWorker(receiveChan, registry)
	go outputMessagesWorker(sendChan, registry)

	fmt.Println("Listening on " + CONN_HOST + ":" + CONN_PORT)
	for {
		conn, err := l.Accept()
		if err != nil {
			fmt.Println("Error accepting: ", err.Error())
			os.Exit(1)
		}

		go MessageStreamListener(messageCodec, conn, receiveChan)
		go MessageStreamWriter(messageCodec, conn, sendChan)
	}

	registry.GetProcess(0).command.Wait()
}

type winsize struct {
	ws_row    uint16
	ws_col    uint16
	ws_xpixel uint16
	ws_ypixel uint16
}

func setSize(fd uintptr, rown, columns, xpixel, ypixel int32) error {
	var ws winsize

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

// Handles incoming requests.
func handleChannel(conn net.Conn, f io.WriteCloser) {
	// Make a buffer to hold incoming data.
	buf := make([]byte, 1024)
	// Read the incoming connection into the buffer.
	// Send a response back to person contacting us.
	//conn.Write(append([]byte("Message received."), buf...))
	for {
		n, err := conn.Read(buf)
		if err != nil {
			fmt.Println("Error reading:", err.Error())
			break
		}

		nw, err := f.Write(buf[:n])
		if n != nw {
			fmt.Println(">", n, nw)
		}
		if err != nil {
			log.Fatal(err)
		}
	}
	// Close the connection when you're done with it.
	conn.Close()
}
