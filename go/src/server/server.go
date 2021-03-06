package main

import (
	"fmt"
	"github.com/fede1024/goavro"
	"log"
	"net"
	"os"
	"reflect"
)

var sockPath = "/tmp/mm.sock"

func processInputMessage(msg *goavro.Record, pReg *ProcessRegistry, conn *Connection) {
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
		processIdRaw, _ := dataRecord.Get("processId")
		processId := int(processIdRaw.(int32))

		if pReg.GetProcess(processId) == nil {
			log.Printf("Process id %d not valid for stdin command\n", processId)
			return
		}
		if pReg.GetProcess(processId).alive {
			pReg.GetProcess(processId).stdin <- bytesRaw.([]byte) // TODO: put a select here?
		}
	} else if messageType == "resize" {
		processIdRaw, _ := dataRecord.Get("processId")
		processId := int(processIdRaw.(int32))

		cols, _ := dataRecord.Get("cols")
		rows, _ := dataRecord.Get("rows")
		xpixel, _ := dataRecord.Get("xpixel")
		ypixel, _ := dataRecord.Get("ypixel")

		if pReg.GetProcess(processId) == nil {
			log.Printf("Process id %d not valid for resize command\n", processId)
			return
		}
		if pReg.GetProcess(processId).alive {
			pReg.GetProcess(processId).SetSize(rows.(int32), cols.(int32), xpixel.(int32), ypixel.(int32))
		}
	} else if messageType == "createProcess" {
		path, err := dataRecord.Get("path")
		cols, _ := dataRecord.Get("cols")
		rows, _ := dataRecord.Get("rows")
		xpixel, _ := dataRecord.Get("xpixel")
		ypixel, _ := dataRecord.Get("ypixel")

		proc, err := NewProcess(path.(string))
		if err != nil {
			log.Println("Error while creating process:", err)
			os.Exit(1)
		}
		proc.SetSize(rows.(int32), cols.(int32), xpixel.(int32), ypixel.(int32))
		if err = proc.Start(); err != nil {
			log.Println("Error while starting process:", err)
			os.Exit(1)
		}
		pReg.AddProcess(proc)
		log.Printf("New process")

		conn.sendChan <- NewProcessCreationMessage(proc.id) // Notifies the sender that the process is up
		conn.FollowProcess(proc)                            // Register the connection as follower
	} else {
		log.Printf("Unknown message type: %s", messageType)
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
				continue // New connection added
			}
			processInputMessage(value.Interface().(*goavro.Record), pReg, caseToConnection[chosen])
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
				continue // New process added
			}
			for _, conn := range cReg.connections {
				if conn.alive && conn.IsFollowing(proc) {
					conn.sendChan <- NewOutputMessage(value.Interface().([]byte), proc.id)
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
	//listener, err := net.Listen("tcp", "localhost:3333")
	listener, err := net.Listen("unix", sockPath)
	if err != nil {
		fmt.Println("Error listening:", err.Error())
		os.Exit(1)
	}
	// Close the listener when the application closes.
	defer listener.Close()

	connRegistry := NewConnectionRegistry()

	go inputMessagesWorker(procRegistry, connRegistry)
	go outputMessagesWorker(procRegistry, connRegistry)

	fmt.Println("Listening on " + sockPath)
	for {
		conn, err := NewConnection(listener)
		if err != nil {
			fmt.Println("Error accepting: ", err.Error())
			os.Exit(1)
		}
		connRegistry.AddConnection(conn)

		log.Printf("Total processes: %d\n", len(procRegistry.processes))
	}
}
