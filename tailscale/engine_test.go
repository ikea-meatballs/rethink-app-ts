package tailscale

import (
	"fmt"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestLoadOrCreateLogIdPersists(t *testing.T) {
	dir := t.TempDir()

	id1, err := loadOrCreateLogID(dir)
	if err != nil {
		t.Fatalf("first load: %v", err)
	}

	id2, err := loadOrCreateLogID(dir)
	if err != nil {
		t.Fatalf("second load: %v", err)
	}

	if id1 != id2 {
		t.Fatalf("log id not stable across loads: %v != %v", id1, id2)
	}
}

func TestLoadOrCreateLogIdNoDirGeneratesFresh(t *testing.T) {
	id, err := loadOrCreateLogID("")
	if err != nil {
		t.Fatalf("load with empty dir: %v", err)
	}
	if id.IsZero() {
		t.Fatal("expected non-zero generated log id")
	}
}

func TestDeadLogId(t *testing.T) {
	id := deadLogID()
	if id.IsZero() {
		t.Fatal("dead log id must not be zero")
	}
	txt, _ := id.MarshalText()
	if len(txt) == 0 {
		t.Fatal("dead log id must marshal")
	}
}

func TestStateDirIsCreated(t *testing.T) {
	base := t.TempDir()
	dir := filepath.Join(base, "nested", "ts")
	if _, err := loadOrCreateLogID(dir); err != nil {
		// creation of the log id may fail only if the dir cannot be made
		t.Logf("logid err: %v", err)
	}
	if _, err := os.Stat(dir); err != nil {
		t.Fatalf("state dir not created: %v", err)
	}
}

// ---- inbound TCP allowlist ----

func inboundDst(port uint16) netip.AddrPort {
	return netip.MustParseAddrPort(fmt.Sprintf("100.64.0.1:%d", port))
}

var inboundSrc = netip.MustParseAddrPort("100.64.0.2:40000")

// assertTCP checks every port in want with the given engine, expecting
// "forward" (nil handler, intercept=false) or "reject" (nil handler,
// intercept=true).
func assertTCP(t *testing.T, e *Engine, ports []uint16, forward bool) {
	t.Helper()
	for _, port := range ports {
		dst := inboundDst(port)
		handler, intercept := e.inboundTCPHandler(inboundSrc, dst)
		if handler != nil {
			t.Errorf("port %d: expected nil handler, got non-nil", port)
		}
		if forward && intercept {
			t.Errorf("port %d: expected intercept=false (forward to 127.0.0.1), got true", port)
		}
		if !forward && !intercept {
			t.Errorf("port %d: expected intercept=true (reject), got false", port)
		}
	}
}

func TestInboundTCPHandlerDefaultsToRejectAll(t *testing.T) {
	// zero-value engine: nil atomic pointer, must reject everything
	assertTCP(t, &Engine{}, []uint16{12070, 12100, 12108, 8080, 1053, 1055, 22, 80, 443, 0, 65535}, false)

	// same after explicit construction via NewEngine-style empty allowlist
	e := &Engine{}
	if err := e.SetInboundTCPPorts(""); err != nil {
		t.Fatalf("empty spec: %v", err)
	}
	assertTCP(t, e, []uint16{12070, 12100, 12108, 8080, 1053, 1055, 22, 80, 443, 0, 65535}, false)
}

func TestInboundTCPHandlerAllowsConfiguredRange(t *testing.T) {
	e := &Engine{}
	if err := e.SetInboundTCPPorts("12070-12108"); err != nil {
		t.Fatalf("set range: %v", err)
	}
	assertTCP(t, e, []uint16{12070, 12100, 12108}, true)
	assertTCP(t, e, []uint16{12069, 12109, 8080, 1053, 1055}, false)
}

func TestInboundTCPHandlerAllowsListWithSpaces(t *testing.T) {
	e := &Engine{}
	if err := e.SetInboundTCPPorts("12070-12108, 8080"); err != nil {
		t.Fatalf("set list: %v", err)
	}
	assertTCP(t, e, []uint16{12070, 12100, 12108, 8080}, true)
	assertTCP(t, e, []uint16{12069, 12109, 8079, 8081, 1053, 1055}, false)
}

func TestSetInboundTCPPortsRejectsReservedPortsAndKeepsPrevious(t *testing.T) {
	for _, spec := range []string{"1053", "1055", "1050-1060", "1000-2000", "1053-1060"} {
		e := &Engine{}
		if err := e.SetInboundTCPPorts("12070-12108"); err != nil {
			t.Fatalf("seed allowlist: %v", err)
		}
		err := e.SetInboundTCPPorts(spec)
		if err == nil {
			t.Fatalf("spec %q: expected error, got nil", spec)
		}
		if !strings.Contains(err.Error(), "reserved") {
			t.Errorf("spec %q: error should name the reserved port, got %v", spec, err)
		}
		// previous allowlist untouched
		assertTCP(t, e, []uint16{12070, 12100, 12108}, true)
	}
}

func TestSetInboundTCPPortsRejectsMalformedAndKeepsPrevious(t *testing.T) {
	for _, spec := range []string{
		"abc", "0", "65536", "12108-12070", "12070-", "-12108",
		"12070,", ",12100", "1-2-3", "12a", "1,,2", "-", "",
	} {
		if spec == "" {
			continue // empty is valid (clears); covered separately
		}
		e := &Engine{}
		if err := e.SetInboundTCPPorts("12070-12108"); err != nil {
			t.Fatalf("seed allowlist: %v", err)
		}
		if err := e.SetInboundTCPPorts(spec); err == nil {
			t.Errorf("spec %q: expected error, got nil", spec)
		}
		// previous allowlist untouched
		assertTCP(t, e, []uint16{12070, 12100, 12108}, true)
		assertTCP(t, e, []uint16{8080}, false)
	}
}

func TestSetInboundTCPPortsClearRestoresRejectAll(t *testing.T) {
	for _, spec := range []string{"", "   ", "\t\n"} {
		e := &Engine{}
		if err := e.SetInboundTCPPorts("12070-12108,8080"); err != nil {
			t.Fatalf("seed allowlist: %v", err)
		}
		if err := e.SetInboundTCPPorts(spec); err != nil {
			t.Fatalf("spec %q: expected nil, got %v", spec, err)
		}
		assertTCP(t, e, []uint16{12070, 12100, 12108, 8080, 1053, 1055}, false)
	}
}

func TestInboundTCPHandlerTakesLiveUpdates(t *testing.T) {
	e := &Engine{}
	// handler captured before any allowlist exists (as NewEngine installs it)
	h := e.inboundTCPHandler
	if _, intercept := h(inboundSrc, inboundDst(12100)); !intercept {
		t.Fatal("expected reject before any allowlist is set")
	}
	if err := e.SetInboundTCPPorts("12100"); err != nil {
		t.Fatalf("set: %v", err)
	}
	if _, intercept := h(inboundSrc, inboundDst(12100)); intercept {
		t.Fatal("expected the same handler to see the updated allowlist")
	}
}

func TestInboundUDPHandlerAlwaysDenies(t *testing.T) {
	e := &Engine{}
	if err := e.SetInboundTCPPorts("12070-12108,8080"); err != nil {
		t.Fatalf("set: %v", err)
	}
	for _, port := range []uint16{1053, 1055, 12070, 12100, 12108, 53} {
		dst := inboundDst(port)
		handler, intercept := inboundUDPHandler(inboundSrc, dst)
		if handler != nil {
			t.Errorf("port %d: expected nil handler, got non-nil", port)
		}
		if !intercept {
			t.Errorf("port %d: expected intercept=true (reject), got false", port)
		}
	}
}

// ---- parseInboundTCPPorts ----

func TestParseInboundTCPPortsEmpty(t *testing.T) {
	for _, spec := range []string{"", "   ", "\t"} {
		ranges, err := parseInboundTCPPorts(spec)
		if err != nil {
			t.Fatalf("spec %q: unexpected err %v", spec, err)
		}
		if len(ranges) != 0 {
			t.Fatalf("spec %q: expected no ranges, got %v", spec, ranges)
		}
	}
}

func TestParseInboundTCPPortsValid(t *testing.T) {
	tests := []struct {
		spec string
		want []portRange
	}{
		{"12100", []portRange{{12100, 12100}}},
		{"12070-12108", []portRange{{12070, 12108}}},
		{" 12070-12108, 8080 ", []portRange{{12070, 12108}, {8080, 8080}}},
		{"1-1,65535", []portRange{{1, 1}, {65535, 65535}}},
		{"80,443,8080-8090", []portRange{{80, 80}, {443, 443}, {8080, 8090}}},
	}
	for _, tc := range tests {
		got, err := parseInboundTCPPorts(tc.spec)
		if err != nil {
			t.Fatalf("spec %q: unexpected err %v", tc.spec, err)
		}
		if len(got) != len(tc.want) {
			t.Fatalf("spec %q: got %v, want %v", tc.spec, got, tc.want)
		}
		for i := range got {
			if got[i] != tc.want[i] {
				t.Errorf("spec %q: range %d = %v, want %v", tc.spec, i, got[i], tc.want[i])
			}
		}
	}
}

func TestParseInboundTCPPortsInvalid(t *testing.T) {
	for _, spec := range []string{
		"abc", "0", "65536", "12108-12070", "12070-", "-12108",
		"12070,", ",12100", "1-2-3", "12a", "1,,2", "-", "1053",
		"1055", "1050-1060", "0-100", "70000",
	} {
		if _, err := parseInboundTCPPorts(spec); err == nil {
			t.Errorf("spec %q: expected error, got nil", spec)
		}
	}
}

func TestParseInboundTCPPortsReservedNamesPort(t *testing.T) {
	for _, spec := range []string{"1053", "1055", "1050-1060"} {
		_, err := parseInboundTCPPorts(spec)
		if err == nil {
			t.Fatalf("spec %q: expected error, got nil", spec)
		}
		if !strings.Contains(err.Error(), "reserved") {
			t.Errorf("spec %q: error should name the reserved port, got %v", spec, err)
		}
	}
}
