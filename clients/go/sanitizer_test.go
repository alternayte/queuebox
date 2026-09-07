package queuebox

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

type redactionCase struct {
	Name           string   `json:"name"`
	Input          string   `json:"input"`
	Expected       *string  `json:"expected"`
	MustNotContain []string `json:"mustNotContain"`
	MustContain    []string `json:"mustContain"`
}

type redactionCorpus struct {
	MaxLength        int             `json:"maxLength"`
	TruncationMarker string          `json:"truncationMarker"`
	Cases            []redactionCase `json:"cases"`
}

// TestTheSharedRedactionCorpus runs the cases that every QueueBox client library runs.
// A library that redacts almost the same as the others is a security defect, not a difference of
// idiom, so the cases live in one file rather than in one language.
func TestTheSharedRedactionCorpus(t *testing.T) {
	corpus := loadCorpus(t)

	if corpus.MaxLength != MaxErrorLength {
		t.Fatalf("the corpus states a maximum length of %d and the library uses %d", corpus.MaxLength, MaxErrorLength)
	}

	if len(corpus.Cases) == 0 {
		t.Fatal("the corpus holds no case")
	}

	for _, testCase := range corpus.Cases {
		t.Run(testCase.Name, func(t *testing.T) {
			got := Sanitize(testCase.Input)

			if testCase.Expected != nil && got != *testCase.Expected {
				t.Errorf("expected %q\n     got %q", *testCase.Expected, got)
			}

			for _, secret := range testCase.MustNotContain {
				if strings.Contains(got, secret) {
					t.Errorf("the secret %q printed in %q", secret, got)
				}
			}

			for _, kept := range testCase.MustContain {
				if !strings.Contains(got, kept) {
					t.Errorf("the text %q was lost from %q", kept, got)
				}
			}
		})
	}
}

func loadCorpus(t *testing.T) redactionCorpus {
	t.Helper()

	// The corpus sits beside the libraries, because it belongs to all of them.
	content, err := os.ReadFile(filepath.Join("..", "redaction-corpus.json"))
	if err != nil {
		t.Fatalf("the corpus did not load: %v", err)
	}

	var corpus redactionCorpus
	if err := json.Unmarshal(content, &corpus); err != nil {
		t.Fatalf("the corpus did not parse: %v", err)
	}

	return corpus
}

func TestALongTextIsTruncated(t *testing.T) {
	got := Sanitize(strings.Repeat("x", 5000))

	if len(got) != MaxErrorLength {
		t.Errorf("the text is %d characters and the maximum is %d", len(got), MaxErrorLength)
	}

	if !strings.HasSuffix(got, truncationMarker) {
		t.Errorf("the text does not end with the marker: %q", got)
	}
}

func TestTheRedactionRunsBeforeTheTruncation(t *testing.T) {
	got := Sanitize("token=leaked-value-123 " + strings.Repeat("x", 5000))

	if strings.Contains(got, "leaked-value-123") {
		t.Error("the secret printed")
	}

	if !strings.HasPrefix(got, "token="+redacted) {
		t.Errorf("the redaction was cut: %q", got)
	}
}

func TestSanitizeErrorRedactsTheWholeChain(t *testing.T) {
	// A driver puts the connection string in the message of the cause, not of the wrapper.
	cause := errors.New("Host=db;Password=hunter2")
	err := fmt.Errorf("the claim failed: %w", cause)

	got := SanitizeError(err)

	if strings.Contains(got, "hunter2") {
		t.Errorf("the password printed: %q", got)
	}

	if !strings.Contains(got, "the claim failed") {
		t.Errorf("the message was lost: %q", got)
	}
}

func TestSanitizeErrorOfNilIsEmpty(t *testing.T) {
	if got := SanitizeError(nil); got != "" {
		t.Errorf("expected an empty text and got %q", got)
	}
}
