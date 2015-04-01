package main

import (
	"fmt"
	"github.com/fede1024/goavro"
	"log"
	"net"
	"os"
	"reflect"
)

const (
	CONN_HOST = "localhost"
	CONN_PORT = "3333"
	CONN_TYPE = "tcp"
)

func inputMessagesWorker(receiveChan <-chan *goavro.Record, registry *ProcessRegistry) {
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

			registry.GetProcess(0).setSize(cols.(int32), rows.(int32), xpixel.(int32), ypixel.(int32))
		}
	}
}

func outputMessagesWorker(sendChan chan<- *goavro.Record, registry *ProcessRegistry) {
	for {
		cases := make([]reflect.SelectCase, 0, len(registry.processes))
		caseToProcess := make([]*Process, 0, len(registry.processes))
		for _, proc := range registry.processes {
			if proc.alive {
				cases = append(cases, reflect.SelectCase{Dir: reflect.SelectRecv, Chan: reflect.ValueOf(proc.stdout)})
				caseToProcess = append(caseToProcess, proc)
			}
		}
		if len(cases) == 0 {
			fmt.Println("No process alive, waiting...")
			<-registry.newProcessChan // Wait for a new process to be allocated
			fmt.Println("New process spawned")
			continue
		}
		chosen, value, ok := reflect.Select(cases)
		if ok == true {
			sendChan <- MakeOutputMessage(value.Interface().([]byte))
		} else {
			fmt.Printf("Process %d is dead\n", chosen)
			caseToProcess[chosen].alive = false
		}
	}
}

func main() {
	// to change the flags on the default logger
	log.SetFlags(log.LstdFlags | log.Lshortfile)

	procRegistry := NewProcessRegistry()
	proc, err := NewProcess("/bin/bash")
	if err != nil {
		fmt.Println("Error while starting process:", err)
		os.Exit(1)
	}
	procRegistry.AddProcess(proc)

	LoadAllCodecs()

	// Listen for incoming connections.
	listener, err := net.Listen(CONN_TYPE, CONN_HOST+":"+CONN_PORT)
	if err != nil {
		fmt.Println("Error listening:", err.Error())
		os.Exit(1)
	}
	// Close the listener when the application closes.
	defer listener.Close()

	//go inputMessagesWorker(receiveChan, procRegistry)
	//go outputMessagesWorker(sendChan, procRegistry)

	fmt.Println("Listening on " + CONN_HOST + ":" + CONN_PORT)
	for {
		conn, err := NewConnection(listener)
		if err != nil {
			fmt.Println("Error accepting: ", err.Error())
			os.Exit(1)
		}
		fmt.Println(conn)

		go func() {
			for msg := range conn.recChan {
				fmt.Println(">>", msg)
			}
		}()
	}
}
