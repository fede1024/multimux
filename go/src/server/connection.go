package main

import (
	"github.com/fede1024/goavro"
	"log"
	"net"
)

// type Producer interface {
// 	GetOutputChannel() chan *interface{}
// }
//
// type ProducerSet interface {
// 	GetProducers() []*Producer
// 	AddProducer() chan *Producer
// }

// CONNECTION

type Connection struct {
	socket            *net.Conn
	recChan, sendChan chan *goavro.Record
	processes         map[int]bool
	alive             bool
}

func (conn *Connection) ReceiveWorker() {
	for {
		message, err := MessageCodec.Decode(*conn.socket)
		if err != nil {
			log.Println("Error decoding:", err)
			conn.Terminate()
			return
		}

		conn.recChan <- message.(*goavro.Record)
	}
}

func (conn *Connection) SendWorker() {
	for message := range conn.sendChan {
		err := MessageCodec.Encode(*conn.socket, message)
		if err != nil {
			log.Println("Error encoding:", err)
			conn.Terminate()
			return
		}
	}
}

func NewConnection(l net.Listener) (*Connection, error) {
	socket, err := l.Accept()
	if err != nil {
		return nil, err
	}

	conn := &Connection{socket: &socket, alive: true}
	conn.recChan = make(chan *goavro.Record)
	conn.sendChan = make(chan *goavro.Record)
	conn.processes = make(map[int]bool)

	go conn.ReceiveWorker()
	go conn.SendWorker()

	return conn, nil
}

func (c *Connection) Terminate() {
	if c.alive {
		(*c.socket).Close()
		close(c.sendChan)
		close(c.recChan)
		c.alive = false
	}
}

func (c *Connection) FollowProcess(proc *Process) {
	c.processes[proc.id] = true
}

func (c *Connection) UnfollowProcess(proc *Process) {
	c.processes[proc.id] = false
}

func (c *Connection) IsFollowing(proc *Process) bool {
	val, found := c.processes[proc.id]
	return found && val
}

// CONNECTION REGISTRY

type ConnectionRegistry struct {
	connections       []*Connection
	newConnectionChan chan *Connection
}

func NewConnectionRegistry() *ConnectionRegistry {
	return &ConnectionRegistry{newConnectionChan: make(chan *Connection)}
}

func (cr *ConnectionRegistry) AddConnection(conn *Connection) {
	cr.connections = append(cr.connections, conn)
	select {
	case cr.newConnectionChan <- conn: // Notify the new connection
	default: // Do nothing if full
	}
}
