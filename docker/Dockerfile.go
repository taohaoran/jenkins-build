# -------------------------------------------
#  Go 应用 - 多阶段 Dockerfile
#  使用方式: docker build --build-arg GO_VERSION=1.22 -f docker/Dockerfile.go .
# -------------------------------------------

# -- 构建阶段 --
FROM golang:${GO_VERSION}-alpine AS builder
ARG GO_VERSION=1.22
WORKDIR /build
RUN apk add --no-cache ca-certificates tzdata
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build \
    -ldflags="-s -w" \
    -o app .

# -- 运行阶段 --
FROM scratch
ARG GO_VERSION=1.22
COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=builder /usr/share/zoneinfo /usr/share/zoneinfo
COPY --from=builder /build/app /app
EXPOSE 8080
ENTRYPOINT ["/app"]
