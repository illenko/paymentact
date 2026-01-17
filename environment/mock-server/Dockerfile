FROM golang:1.23-alpine AS builder

WORKDIR /app
COPY go.mod .
COPY main.go .

RUN go build -o mock-server .

FROM alpine:latest

WORKDIR /app
COPY --from=builder /app/mock-server .

EXPOSE 8090

CMD ["./mock-server"]
