package queuebox

import (
	"strings"
	"testing"
)

func BenchmarkSanitizeOrdinaryMessage(b *testing.B) {
	for b.Loop() {
		Sanitize("Connection reset by peer after 3 attempts on exchange orders")
	}
}

func BenchmarkSanitizeConnectionString(b *testing.B) {
	for b.Loop() {
		Sanitize("connect failed: Host=db;Username=app;Password=hunter2;Database=queuebox")
	}
}

func BenchmarkSanitizeLongText(b *testing.B) {
	text := "amqp://user:aa  bb@rabbit:5672/vh " + strings.Repeat("x", 5000)

	for b.Loop() {
		Sanitize(text)
	}
}
