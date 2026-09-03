package trafficshaping

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"strconv"
)

const (
	maximumProfileBytes = 512
	minimumRateKbps     = uint64(1)
	maximumRateKbps     = uint64(1_000_000)
	minimumProfileIDLen = 3
	maximumProfileIDLen = 64
)

var errInvalidProfile = errors.New("traffic shaping profile is invalid")

type appliedProfile struct {
	id           string
	digest       string
	uplinkKbps   *uint64
	downlinkKbps *uint64
}

func parseCanonicalProfile(input []byte) (*appliedProfile, error) {
	if len(input) == 0 || len(input) > maximumProfileBytes {
		return nil, errInvalidProfile
	}

	var members map[string]json.RawMessage
	decoder := json.NewDecoder(bytes.NewReader(input))
	decoder.UseNumber()
	if err := decoder.Decode(&members); err != nil || members == nil || len(members) != 3 {
		return nil, errInvalidProfile
	}
	if decoder.More() {
		return nil, errInvalidProfile
	}
	var trailing any
	if err := decoder.Decode(&trailing); err == nil {
		return nil, errInvalidProfile
	}

	idRaw, hasID := members["id"]
	uplinkRaw, hasUplink := members["uplink_kbps"]
	downlinkRaw, hasDownlink := members["downlink_kbps"]
	if !hasID || !hasUplink || !hasDownlink {
		return nil, errInvalidProfile
	}

	var id string
	if err := json.Unmarshal(idRaw, &id); err != nil || !validProfileID(id) {
		return nil, errInvalidProfile
	}
	uplink, err := parseCanonicalRate(uplinkRaw)
	if err != nil {
		return nil, errInvalidProfile
	}
	downlink, err := parseCanonicalRate(downlinkRaw)
	if err != nil {
		return nil, errInvalidProfile
	}

	canonical := canonicalProfileBytes(id, uplink, downlink)
	if !bytes.Equal(input, canonical) {
		return nil, errInvalidProfile
	}
	digest := sha256.Sum256(input)
	return &appliedProfile{
		id:           id,
		digest:       hex.EncodeToString(digest[:]),
		uplinkKbps:   uplink,
		downlinkKbps: downlink,
	}, nil
}

func parseCanonicalRate(raw json.RawMessage) (*uint64, error) {
	if bytes.Equal(raw, []byte("null")) {
		return nil, nil
	}
	if len(raw) == 0 || raw[0] < '1' || raw[0] > '9' {
		return nil, errInvalidProfile
	}
	for _, b := range raw[1:] {
		if b < '0' || b > '9' {
			return nil, errInvalidProfile
		}
	}
	value, err := strconv.ParseUint(string(raw), 10, 64)
	if err != nil || value < minimumRateKbps || value > maximumRateKbps {
		return nil, errInvalidProfile
	}
	return &value, nil
}

func validProfileID(id string) bool {
	if len(id) < minimumProfileIDLen || len(id) > maximumProfileIDLen {
		return false
	}
	for index, char := range []byte(id) {
		isAlpha := char >= 'a' && char <= 'z'
		isDigit := char >= '0' && char <= '9'
		if index == 0 {
			if !isAlpha && !isDigit {
				return false
			}
			continue
		}
		if !isAlpha && !isDigit && char != '-' {
			return false
		}
	}
	return true
}

func canonicalProfileBytes(id string, uplink, downlink *uint64) []byte {
	result := make([]byte, 0, 96)
	result = append(result, `{"downlink_kbps":`...)
	result = appendCanonicalRate(result, downlink)
	result = append(result, `,"id":"`...)
	result = append(result, id...)
	result = append(result, `","uplink_kbps":`...)
	result = appendCanonicalRate(result, uplink)
	result = append(result, '}')
	return result
}

func appendCanonicalRate(target []byte, rate *uint64) []byte {
	if rate == nil {
		return append(target, "null"...)
	}
	return strconv.AppendUint(target, *rate, 10)
}
