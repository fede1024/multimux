package main

import (
	"bufio"
	"fmt"
	"github.com/kr/pty"
	"github.com/linkedin/goavro"
	"io"
	"log"
	"net"
	"os"
	"os/exec"
	"syscall"
	"unsafe"
	//	"strings"
)

var messageSchema goavro.RecordSetter
var messageCodec goavro.Codec

func printPipe(pipe io.ReadCloser) {
	buf := make([]byte, 1024)
	for {
		reqLen, err := pipe.Read(buf)
		if err != nil {
			log.Fatal(err)
			break
		}
		s := string(buf[:reqLen])
		fmt.Printf(s)
	}
}

func readToPipe(pipe io.WriteCloser) {
	reader := bufio.NewReader(os.Stdin)

	for {
		text, err := reader.ReadString('\n')
		if err != nil {
			log.Fatal(err)
			break
		}
		io.WriteString(pipe, text)
	}
}

func exec_bash() {
	cmd := exec.Command("bash")

	processOut, err := cmd.StdoutPipe()
	if err != nil {
		log.Fatal(err)
	}
	defer processOut.Close()

	processErr, err := cmd.StderrPipe()
	if err != nil {
		log.Fatal(err)
	}
	defer processErr.Close()

	processIn, err := cmd.StdinPipe()
	if err != nil {
		fmt.Println(err)
	}
	defer processIn.Close()

	if err := cmd.Start(); err != nil {
		log.Fatal(err)
	}

	go printPipe(processOut)
	go printPipe(processErr)
	go readToPipe(processIn)

	cmd.Wait()

	fmt.Printf("Done\n")
}

const (
	CONN_HOST = "localhost"
	CONN_PORT = "3333"
	CONN_TYPE = "tcp"
)

func printFile(pipe io.ReadCloser) {
	//buf := make([]byte, 1024)
	for {
		//reqLen, err := pipe.Read(buf)
		_, err := io.Copy(os.Stdout, pipe)
		if err != nil {
			log.Fatal(err)
			break
		}
		//s := string(buf[:reqLen])
		//fmt.Printf(s)
		// os.Stdout.Write(buf[:reqLen])
	}
}

func printFileTo(pipe io.ReadCloser, out net.Conn) {
	//buf := make([]byte, 1024)
	for {
		//reqLen, err := pipe.Read(buf)
		_, err := io.Copy(out, pipe)
		if err != nil {
			log.Fatal(err)
			break
		}
		//s := string(buf[:reqLen])
		//fmt.Printf(s)
		// os.Stdout.Write(buf[:reqLen])
	}
}

func readToFile(pipe io.WriteCloser) {
	reader := bufio.NewReader(os.Stdin)

	for {
		text, err := reader.ReadString('\n')
		if err != nil {
			log.Fatal(err)
			break
		}
		//io.WriteString(pipe, text)
		pipe.Write([]byte(text))
	}
}

func Start(c *exec.Cmd) (newPty *os.File, err error) {
	newPty, tty, err := pty.Open()
	if err != nil {
		return nil, err
	}
	defer tty.Close()
	c.Stdout = tty
	c.Stdin = tty
	c.Stderr = tty
	c.SysProcAttr = &syscall.SysProcAttr{Setctty: true, Setsid: true}
	err = c.Start()
	if err != nil {
		newPty.Close()
		return nil, err
	}

	fmt.Printf(newPty.Name() + "\n")
	fmt.Printf(tty.Name() + "\n")
	rows, cols, _ := pty.Getsize(tty)
	fmt.Printf(">> ", rows, cols, "\n")
	setSize(tty.Fd(), 20, 80)
	rows, cols, _ = pty.Getsize(tty)
	fmt.Printf(">> ", rows, cols, "\n")

	return newPty, err
}

func ProcessReceiver(receiveChan <-chan *goavro.Record, f io.Writer) {
	for msg := range receiveChan {
		messageType, err := msg.Get("messageType")
		if err != nil {
			fmt.Println(err)
			return
		}
		if messageType == "input" {
			dataRecord, err := msg.Get("data")
			if err != nil {
				fmt.Println(err)
				return
			}
			data := dataRecord.([]byte)
			nw, err := f.Write(data)
			if err != nil {
				log.Fatal(err)
				break
			}
			if len(data) != nw {
				panic("Fix here")
			}
		}
	}
}

func makeOutputMessage(data []byte) *goavro.Record {
	record, err := goavro.NewRecord(messageSchema)
	if err != nil {
		log.Fatal(err)
		return nil
	}
	record.Set("messageType", "output")
	record.Set("data", data)

	return record
}

//func ProcessSender(sendChan chan<- *goavro.Record, f io.Reader) {
func ProcessSender(sendChan chan *goavro.Record, f io.Reader) {
	for {
		buf := make([]byte, 1024)
		reqLen, err := f.Read(buf)
		if err != nil {
			log.Fatal(err)
			break
		}
		record := makeOutputMessage(buf[:reqLen])

		sendChan <- record
	}
}

func main() {
	//c := exec.Command("grep", "--color=auto", "bar")
	c := exec.Command("/bin/bash")
	//c := exec.Command("cat", "/home/fede/test")
	f, err := Start(c)
	if err != nil {
		panic(err)
	}

	//go func() {
	//	f.Write([]byte("ls\n"))
	//}()

	receiveChan := make(chan *goavro.Record)
	sendChan := make(chan *goavro.Record)
	messageCodec, messageSchema, err = MessageCodec()
	if err != nil {
		fmt.Println("Can't create message codec")
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

	go ProcessReceiver(receiveChan, f)
	go ProcessSender(sendChan, f)

	fmt.Println("Listening on " + CONN_HOST + ":" + CONN_PORT)
	for {
		conn, err := l.Accept()
		if err != nil {
			fmt.Println("Error accepting: ", err.Error())
			os.Exit(1)
		}

		go MessageStreamListener(messageCodec, conn, receiveChan)
		go MessageStreamWriter(messageCodec, conn, sendChan)
		//go printFileTo(f, conn)
		//go ProcessListener(f, conn)
	}

	c.Wait()
}

type winsize struct {
	ws_row    uint16
	ws_col    uint16
	ws_xpixel uint16
	ws_ypixel uint16
}

func setSize(fd uintptr, rown, columns int) error {
	var ws winsize

	_, _, errno := syscall.Syscall(syscall.SYS_IOCTL, fd, syscall.TIOCGWINSZ, uintptr(unsafe.Pointer(&ws)))
	if errno != 0 {
		return syscall.Errno(errno)
	}

	ws.ws_col = uint16(columns)
	ws.ws_row = uint16(rown)

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
			break
		}
	}
	// Close the connection when you're done with it.
	conn.Close()
}
