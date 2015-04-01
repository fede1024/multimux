package main

import (
	"fmt"
	"github.com/fede1024/goavro"
	"net"
)

// CONNECTION

type Connection struct {
	socket            *net.Conn
	recChan, sendChan chan *goavro.Record
}

func (conn *Connection) ReadWorker() {
	for {
		message, err := MessageCodec.Decode(*conn.socket)
		//fmt.Println("REC", message)
		if err != nil {
			fmt.Println("Error decoding:", err)
			return
		}

		conn.recChan <- message.(*goavro.Record)
	}
}

func (conn *Connection) WriteWorker() {
	for message := range conn.sendChan {
		err := MessageCodec.Encode(*conn.socket, message)
		if err != nil {
			fmt.Println("Error encoding:", err)
			return
		}
	}
}

func NewConnection(l net.Listener) (*Connection, error) {
	socket, err := l.Accept()
	if err != nil {
		return nil, err
	}

	conn := &Connection{socket: &socket}
	conn.recChan = make(chan *goavro.Record)
	conn.sendChan = make(chan *goavro.Record)

	go conn.ReadWorker()
	go conn.WriteWorker()

	return conn, nil
}
