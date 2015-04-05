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
var RegisterToProcessSchema goavro.RecordSetter
var RegisterToProcessCodec goavro.Codec
var MessageSchema goavro.RecordSetter
var MessageCodec goavro.Codec

func LoadAllCodecs() {
	st := goavro.NewSymtab()
	InputOutputCodec, InputOutputSchema = LoadCodec(st, "../../../avro/InputOutput.avsc")
	ResizeCodec, ResizeSchema = LoadCodec(st, "../../../avro/Resize.avsc")
	RegisterToProcessCodec, RegisterToProcessSchema = LoadCodec(st, "../../../avro/RegisterToProcess.avsc")
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

func MakeOutputMessage(data []byte, procId int) *goavro.Record {
	record, err := goavro.NewRecord(MessageSchema)
	if err != nil {
		log.Fatal(err)
	}

	inputOutput, err := goavro.NewRecord(InputOutputSchema)
	if err != nil {
		log.Fatal(err)
	}
	inputOutput.Set("process", int32(procId))
	inputOutput.Set("bytes", data)
	record.Set("messageType", "stdout")
	record.Set("data", inputOutput)

	return record
}
