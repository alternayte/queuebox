package queuebox

import "encoding/json"

// Message is one claimed inbox row, as the handler sees it.
//
// The claim token is deliberately absent. The library owns the token, because a handler that
// could reach it could complete a message out of band.
type Message struct {
	// ID is the inbox row identifier.
	ID string
	// Source is the source name.
	Source string
	// IdempotencyKey is the deduplication key. The full identity is the source and this key
	// together, because two sources can send the same event ID and mean different events.
	IdempotencyKey string
	// AggregateID is nullable.
	AggregateID *string
	// EventType is nullable.
	EventType *string
	// Payload is the JSON body, as it was stored.
	Payload json.RawMessage
	// Attempt is the delivery counter. It is zero on the first delivery.
	Attempt int
	// CorrelationID is nullable, and it is for logs.
	CorrelationID *string
}

// UnmarshalPayload parses the JSON body into the value that target points at.
func (m Message) UnmarshalPayload(target any) error {
	return json.Unmarshal(m.Payload, target)
}
