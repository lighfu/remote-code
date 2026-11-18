package host

import (
	"encoding/base64"
	"errors"
)

func attachmentPath(id string) (string, error) {
	if id == "" {
		return "", errors.New("Failed to save image attachment")
	}
	decoded, err := base64.RawURLEncoding.DecodeString(id)
	return string(decoded), err
}
