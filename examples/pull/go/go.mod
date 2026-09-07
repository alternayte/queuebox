module github.com/alternayte/queuebox/examples/pull/go

go 1.25.0

require (
	github.com/alternayte/queuebox/clients/go v0.0.0
	github.com/jackc/pgx/v5 v5.10.0
)

require (
	github.com/dlclark/regexp2 v1.12.0 // indirect
	github.com/jackc/pgpassfile v1.0.0 // indirect
	github.com/jackc/pgservicefile v0.0.0-20240606120523-5a60cdf6a761 // indirect
	github.com/jackc/puddle/v2 v2.2.2 // indirect
	golang.org/x/sync v0.22.0 // indirect
	golang.org/x/text v0.41.0 // indirect
)

// The example builds against the library in this repository. A consumer outside the repository
// writes `go get github.com/alternayte/queuebox/clients/go` and needs no replace.
replace github.com/alternayte/queuebox/clients/go => ../../../clients/go
