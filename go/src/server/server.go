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

func processInputMessage(msg *goavro.Record, pReg *ProcessRegistry) {
	messageType, err := msg.Get("messageType")
	if err != nil {
		fmt.Println(err)
		return
	}
	payload, err := msg.Get("data")
	if err != nil {
		fmt.Println(err)
		return
	}
	dataRecord := payload.(*goavro.Record)
	if messageType == "input" {
		bytesRaw, _ := dataRecord.Get("bytes")
		pReg.GetProcess(0).stdin <- bytesRaw.([]byte)
	} else if messageType == "resize" {
		cols, _ := dataRecord.Get("cols")
		rows, _ := dataRecord.Get("rows")
		xpixel, _ := dataRecord.Get("xpixel")
		ypixel, _ := dataRecord.Get("ypixel")

		pReg.GetProcess(0).setSize(cols.(int32), rows.(int32), xpixel.(int32), ypixel.(int32))
	}
}

func inputMessagesWorker(pReg *ProcessRegistry, cReg *ConnectionRegistry) {
	for {
		cases := make([]reflect.SelectCase, 0, len(cReg.connections)+1)
		caseToConnection := make([]*Connection, 0, len(cReg.connections)+1)
		cases = append(cases, reflect.SelectCase{Dir: reflect.SelectRecv, Chan: reflect.ValueOf(cReg.newConnectionChan)})
		caseToConnection = append(caseToConnection, nil)
		for _, conn := range cReg.connections {
			if conn.alive {
				cases = append(cases, reflect.SelectCase{Dir: reflect.SelectRecv, Chan: reflect.ValueOf(conn.recChan)})
				caseToConnection = append(caseToConnection, conn)
			}
		}
		chosen, value, ok := reflect.Select(cases)
		if ok == true {
			if chosen == 0 {
				log.Println("New connection notified")
				continue
			}
			processInputMessage(value.Interface().(*goavro.Record), pReg)
		} else {
			if chosen == 0 {
				log.Fatal("This should never happen")
			}
			log.Println("Connection closed")
			caseToConnection[chosen].alive = false
		}
	}
}

func outputMessagesWorker(pReg *ProcessRegistry, cReg *ConnectionRegistry) {
	for {
		cases := make([]reflect.SelectCase, 0, len(pReg.processes))
		caseToProcess := make([]*Process, 0, len(pReg.processes))
		for _, proc := range pReg.processes {
			if proc.alive {
				cases = append(cases, reflect.SelectCase{Dir: reflect.SelectRecv, Chan: reflect.ValueOf(proc.stdout)})
				caseToProcess = append(caseToProcess, proc)
			}
		}
		if len(cases) == 0 {
			log.Println("No process alive, waiting...")
			<-pReg.newProcessChan // Wait for a new process to be allocated
			log.Println("New process spawned")
			continue
		}
		chosen, value, ok := reflect.Select(cases)
		if ok == true {
			for _, conn := range cReg.connections {
				if conn.alive {
					conn.sendChan <- MakeOutputMessage(value.Interface().([]byte))
				}
			}
		} else {
			log.Printf("Process %d is dead\n", chosen)
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

	connRegistry := NewConnectionRegistry()

	go inputMessagesWorker(procRegistry, connRegistry)
	go outputMessagesWorker(procRegistry, connRegistry)

	fmt.Println("Listening on " + CONN_HOST + ":" + CONN_PORT)
	for {
		conn, err := NewConnection(listener)
		if err != nil {
			fmt.Println("Error accepting: ", err.Error())
			os.Exit(1)
		}
		connRegistry.AddConnection(conn)
	}
}
