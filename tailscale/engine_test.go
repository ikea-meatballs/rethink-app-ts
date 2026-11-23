package tailscale

import (
	"fmt"
	"net/netip"
	"os"
	"path/filepath"
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

func inboundDst(port uint16) netip.AddrPort {
	return netip.MustParseAddrPort(fmt.Sprintf("100.64.0.1:%d", port))
}

func TestInboundTCPHandlerAllowsFluxPorts(t *testing.T) {
	src := netip.MustParseAddrPort("100.64.0.2:40000")
	for _, port := range []uint16{12070, 12100, 12108} {
		dst := inboundDst(port)
		handler, intercept := inboundTCPHandler(src, dst)
		if handler != nil {
			t.Errorf("port %d: expected nil handler, got non-nil", port)
		}
		if intercept {
			t.Errorf("port %d: expected intercept=false (forward to 127.0.0.1), got true", port)
		}
	}
}

func TestInboundTCPHandlerDeniesOtherPorts(t *testing.T) {
	src := netip.MustParseAddrPort("100.64.0.2:40000")
	for _, port := range []uint16{12069, 12109, 1053, 1055, 22, 80, 443, 0, 65535} {
		dst := inboundDst(port)
		handler, intercept := inboundTCPHandler(src, dst)
		if handler != nil {
			t.Errorf("port %d: expected nil handler, got non-nil", port)
		}
		if !intercept {
			t.Errorf("port %d: expected intercept=true (reject), got false", port)
		}
	}
}

func TestInboundUDPHandlerAlwaysDenies(t *testing.T) {
	src := netip.MustParseAddrPort("100.64.0.2:40000")
	for _, port := range []uint16{1053, 1055, 12070, 12100, 12108, 53} {
		dst := inboundDst(port)
		handler, intercept := inboundUDPHandler(src, dst)
		if handler != nil {
			t.Errorf("port %d: expected nil handler, got non-nil", port)
		}
		if !intercept {
			t.Errorf("port %d: expected intercept=true (reject), got false", port)
		}
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
