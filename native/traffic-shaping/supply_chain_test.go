package trafficshaping

import (
	"encoding/json"
	"os"
	"runtime/debug"
	"strings"
	"testing"
)

func TestPinnedSourceDependenciesMatchSBOMInput(t *testing.T) {
	goSum, err := os.ReadFile("go.sum")
	if err != nil {
		t.Fatal(err)
	}
	for _, required := range []string{
		"github.com/xjasonlyu/tun2socks/v2 v2.7.0 h1:fYEN0Q1sSanuoID8xvUTsMODbKLJJH/J5ywYoMRxIPw=",
		"github.com/xjasonlyu/tun2socks/v2 v2.7.0/go.mod h1:XHgJ3wbs63mZxGbJawt6jjNX65aPDl+Qhn2ePPEE4KI=",
		"golang.org/x/mobile v0.0.0-20260821190718-4776eadac327 h1:D/wiQ6AoTYjDtSD0HMPhU8O40NUP8EF0UmDhIYCnG4I=",
		"golang.org/x/mobile v0.0.0-20260821190718-4776eadac327/go.mod h1:D9q8rgXu13Q3uuM+Vuy6F/DG1WF/giTPLtqQ9on5B1M=",
	} {
		if !containsExactLine(string(goSum), required) {
			t.Fatalf("go.sum is missing pinned source checksum %q", required)
		}
	}

	inputBytes, err := os.ReadFile("sbom-input.json")
	if err != nil {
		t.Fatal(err)
	}
	var input struct {
		AndroidNDK         string `json:"android_ndk"`
		AndroidNDKRevision string `json:"android_ndk_revision"`
		GoToolchain        string `json:"go_toolchain"`
		Proxy              string `json:"proxy"`
		SumDatabase        string `json:"sum_database"`
		Components         []struct {
			License       string `json:"license"`
			LicenseFile   string `json:"license_file"`
			LicenseSHA256 string `json:"license_sha256"`
			Module        string `json:"module"`
			Role          string `json:"role"`
			Version       string `json:"version"`
			Sum           string `json:"sum"`
			GoModSum      string `json:"go_mod_sum"`
		} `json:"components"`
	}
	if err := json.Unmarshal(inputBytes, &input); err != nil {
		t.Fatal(err)
	}
	if input.AndroidNDK != "30.0.14904198" || input.AndroidNDKRevision != "30.0.14904198-beta1" || input.GoToolchain != "go1.26.3" {
		t.Fatalf("unexpected pinned toolchain: %+v", input)
	}
	if input.Proxy != "https://proxy.golang.org" || input.SumDatabase != "sum.golang.org" {
		t.Fatalf("dependency policy permits an unverified source: %+v", input)
	}
	if len(input.Components) != 11 {
		t.Fatalf("linked/build component count = %d, want 11", len(input.Components))
	}
	seen := map[string]bool{}
	for _, component := range input.Components {
		if component.Module == "" || component.Version == "" || component.License == "" || component.Role == "" ||
			component.LicenseFile == "" || len(component.LicenseSHA256) != 64 {
			t.Fatalf("incomplete SBOM component: %+v", component)
		}
		if seen[component.Module] {
			t.Fatalf("duplicate SBOM component: %s", component.Module)
		}
		seen[component.Module] = true
		if !containsExactLine(string(goSum), component.Module+" "+component.Version+" "+component.Sum) {
			t.Fatalf("SBOM component sum does not match go.sum: %+v", component)
		}
		if !containsExactLine(string(goSum), component.Module+" "+component.Version+"/go.mod "+component.GoModSum) {
			t.Fatalf("SBOM component go.mod sum does not match go.sum: %+v", component)
		}
	}
	buildInfo, ok := debug.ReadBuildInfo()
	if !ok {
		t.Fatal("Go build information is unavailable")
	}
	for _, dependency := range buildInfo.Deps {
		if !seen[dependency.Path] {
			t.Fatalf("linked module is absent from SBOM input: %s@%s", dependency.Path, dependency.Version)
		}
	}
	if !seen["golang.org/x/mobile"] {
		t.Fatal("gomobile build/runtime module is absent from SBOM input")
	}
}

func containsExactLine(document, line string) bool {
	for _, candidate := range strings.Split(document, "\n") {
		if candidate == line {
			return true
		}
	}
	return false
}
