package trafficshaping

import (
	"crypto/sha256"
	"encoding/hex"
	"testing"
)

func TestParseCanonicalProfile(t *testing.T) {
	input := []byte(`{"downlink_kbps":1024,"id":"slow-network","uplink_kbps":256}`)
	profile, err := parseCanonicalProfile(input)
	if err != nil {
		t.Fatalf("parse profile: %v", err)
	}
	digest := sha256.Sum256(input)
	if profile.digest != hex.EncodeToString(digest[:]) {
		t.Fatalf("unexpected digest %q", profile.digest)
	}
	if profile.id != "slow-network" || profile.uplinkKbps == nil || *profile.uplinkKbps != 256 {
		t.Fatalf("unexpected parsed profile: %#v", profile)
	}
	if profile.downlinkKbps == nil || *profile.downlinkKbps != 1024 {
		t.Fatalf("unexpected downlink profile: %#v", profile)
	}
}

func TestParseCanonicalUnlimitedProfile(t *testing.T) {
	profile, err := parseCanonicalProfile([]byte(`{"downlink_kbps":null,"id":"baseline","uplink_kbps":null}`))
	if err != nil {
		t.Fatalf("parse profile: %v", err)
	}
	if profile.uplinkKbps != nil || profile.downlinkKbps != nil {
		t.Fatalf("unlimited profile became limited: %#v", profile)
	}
}

func TestParseCanonicalProfileRejectsHostileShapes(t *testing.T) {
	cases := []string{
		``,
		` {"downlink_kbps":null,"id":"baseline","uplink_kbps":null}`,
		`{"id":"baseline","downlink_kbps":null,"uplink_kbps":null}`,
		`{"downlink_kbps":null,"id":"baseline","uplink_kbps":null}\n`,
		`{"downlink_kbps":null,"id":"baseline","uplink_kbps":null,"x":1}`,
		`{"downlink_kbps":null,"id":"baseline"}`,
		`{"downlink_kbps":null,"id":"baseline","id":"other","uplink_kbps":null}`,
		`{"downlink_kbps":0,"id":"baseline","uplink_kbps":null}`,
		`{"downlink_kbps":1000001,"id":"baseline","uplink_kbps":null}`,
		`{"downlink_kbps":1e3,"id":"baseline","uplink_kbps":null}`,
		`{"downlink_kbps":null,"id":"bad profile","uplink_kbps":null}`,
		`{"downlink_kbps":null,"id":"_bad","uplink_kbps":null}`,
		`{"downlink_kbps":null,"id":"UPPER","uplink_kbps":null}`,
		`{"downlink_kbps":null,"id":"a.b","uplink_kbps":null}`,
		`{"downlink_kbps":null,"id":"ab","uplink_kbps":null}`,
	}
	for _, input := range cases {
		if _, err := parseCanonicalProfile([]byte(input)); err == nil {
			t.Errorf("accepted non-canonical or invalid profile %q", input)
		}
	}
}
