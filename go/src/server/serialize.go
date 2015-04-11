package main

import (
	"github.com/fede1024/goavro"
	"io/ioutil"
	"log"
)

var InputOutputSchema goavro.RecordSetter
var InputOutputCodec goavro.Codec
var ResizeSchema goavro.RecordSetter
var ResizeCodec goavro.Codec
var CreateProcessSchema goavro.RecordSetter
var CreateProcessCodec goavro.Codec
var AttachToProcessSchema goavro.RecordSetter
var AttachToProcessCodec goavro.Codec
var MessageSchema goavro.RecordSetter
var MessageCodec goavro.Codec

func LoadAllCodecs() {
	st := goavro.NewSymtab()
	InputOutputCodec, InputOutputSchema = LoadCodec(st, "../../../avro/InputOutput.avsc")
	ResizeCodec, ResizeSchema = LoadCodec(st, "../../../avro/Resize.avsc")
	CreateProcessCodec, CreateProcessSchema = LoadCodec(st, "../../../avro/CreateProcess.avsc")
	AttachToProcessCodec, AttachToProcessSchema = LoadCodec(st, "../../../avro/AttachToProcess.avsc")
	MessageCodec, MessageSchema = LoadCodec(st, "../../../avro/Message.avsc")
}

func LoadCodec(st goavro.Symtab, path string) (goavro.Codec, goavro.RecordSetter) {
	avscJson, err := ioutil.ReadFile(path)
	if err != nil {
		log.Fatal("Can't open avro schema", err.Error())
	}

	jsonString := string(avscJson)

	schema := goavro.RecordSchema(jsonString)
	codec, err := st.NewCodec(jsonString)
	if err != nil {
		log.Fatal("Can't decode avro schema", err.Error())
	}

	return codec, schema
}

func NewOutputMessage(data []byte, procId int) *goavro.Record {
	record, err := goavro.NewRecord(MessageSchema)
	if err != nil {
		log.Fatal(err)
	}

	inputOutput, err := goavro.NewRecord(InputOutputSchema)
	if err != nil {
		log.Fatal(err)
	}
	inputOutput.Set("processId", int32(procId))
	inputOutput.Set("bytes", data)
	record.Set("messageType", "stdout")
	record.Set("data", inputOutput)

	return record
}

func NewProcessCreationMessage(procId int) *goavro.Record {
	record, err := goavro.NewRecord(MessageSchema)
	if err != nil {
		log.Fatal(err)
	}

	createProcess, err := goavro.NewRecord(CreateProcessSchema)
	if err != nil {
		log.Fatal(err)
	}

	createProcess.Set("processId", int32(procId))
	record.Set("messageType", "createProcess")
	record.Set("data", createProcess)

	return record
}
