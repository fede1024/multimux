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
	if messageType == "stdin" {
		bytesRaw, _ := dataRecord.Get("bytes")
		processIdRaw, _ := dataRecord.Get("process")
		processId := int(processIdRaw.(int32))
		log.Printf("msg for %d out of %d\n", processId, len(pReg.processes))
		if pReg.GetProcess(processId).alive {
			pReg.GetProcess(processId).stdin <- bytesRaw.([]byte)
		}
	} else if messageType == "resize" {
		processIdRaw, _ := dataRecord.Get("process")
		processId := int(processIdRaw.(int32))

		cols, _ := dataRecord.Get("cols")
		rows, _ := dataRecord.Get("rows")
		xpixel, _ := dataRecord.Get("xpixel")
		ypixel, _ := dataRecord.Get("ypixel")

		if pReg.GetProcess(processId).alive {
			pReg.GetProcess(processId).setSize(cols.(int32), rows.(int32), xpixel.(int32), ypixel.(int32))
		}
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
		}
	}
}

func outputMessagesWorker(pReg *ProcessRegistry, cReg *ConnectionRegistry) {
	for {
		cases := make([]reflect.SelectCase, 0, len(pReg.processes)+1)
		caseToProcess := make([]*Process, 0, len(pReg.processes)+1)
		cases = append(cases, reflect.SelectCase{Dir: reflect.SelectRecv, Chan: reflect.ValueOf(pReg.newProcessChan)})
		caseToProcess = append(caseToProcess, nil)
		for _, proc := range pReg.processes {
			if proc.alive {
				cases = append(cases, reflect.SelectCase{Dir: reflect.SelectRecv, Chan: reflect.ValueOf(proc.stdout)})
				caseToProcess = append(caseToProcess, proc)
			}
		}
		chosen, value, ok := reflect.Select(cases)
		proc := caseToProcess[chosen]
		if ok == true {
			if chosen == 0 {
				log.Println("New process notified")
				continue
			}
			for _, conn := range cReg.connections {
				if conn.alive {
					conn.sendChan <- MakeOutputMessage(value.Interface().([]byte), proc.id)
				}
			}
		} else {
			if chosen == 0 {
				log.Fatal("This should never happen")
			}
			log.Printf("Process %d is dead\n", chosen)
		}
	}
}

func main() {
	// to change the flags on the default logger
	log.SetFlags(log.LstdFlags | log.Lshortfile)

	procRegistry := NewProcessRegistry()

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

		proc, err := NewProcess("/bin/bash")
		if err != nil {
			fmt.Println("Error while starting process:", err)
			os.Exit(1)
		}
		procRegistry.AddProcess(proc)
		log.Printf("Total processes: %d\n", len(procRegistry.processes))
	}
}
