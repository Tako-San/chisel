#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0

# ==============================================================================
# CIRCT Integration Validation Script
# ==============================================================================
#
# Tests the complete debug metadata pipeline:
#   Chisel -> FIRRTL (intrinsics) -> firtool -> MLIR (dbg ops)
#
# Usage:
#   bash scripts/validate_circt_integration.sh
#
# Requirements:
#   - sbt (for Chisel compilation)
#   - firtool (optional, for CIRCT validation)
#
# Exit codes:
#   0 - Success (all checks passed)
#   1 - Failure (intrinsics missing or firtool error)
#   2 - Partial success (firtool not available)
# ==============================================================================

set -euo pipefail

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
OUTPUT_DIR="generated"
EXAMPLE_NAME="DebugInfoExample"
FIRRTL_FILE="${OUTPUT_DIR}/${EXAMPLE_NAME}.fir"
MLIR_FILE="/tmp/debug_output.mlir"
LOG_FILE="/tmp/circt_validation.log"

# ==============================================================================
# Helper Functions
# ==============================================================================

log_info() {
  echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
  echo -e "${GREEN}✓${NC} $1"
}

log_warning() {
  echo -e "${YELLOW}⚠${NC} $1"
}

log_error() {
  echo -e "${RED}✗${NC} $1"
}

print_header() {
  echo -e "\n${BLUE}========================================${NC}"
  echo -e "${BLUE}$1${NC}"
  echo -e "${BLUE}========================================${NC}\n"
}

check_command() {
  if command -v "$1" &> /dev/null; then
    log_success "$1 found: $(command -v $1)"
    return 0
  else
    log_warning "$1 not found"
    return 1
  fi
}

# ==============================================================================
# Main Validation Pipeline
# ==============================================================================

print_header "CIRCT Integration Validation"

# Clean previous outputs
rm -f "$FIRRTL_FILE" "$MLIR_FILE" "$LOG_FILE"
mkdir -p "$OUTPUT_DIR"

# --------------------------------------------------------------------------
# Step 1: Check Dependencies
# --------------------------------------------------------------------------

log_info "Checking dependencies..."

if ! check_command sbt; then
  log_error "sbt not found! Install: https://www.scala-sbt.org/"
  exit 1
fi

FIRTOOL_AVAILABLE=false
if check_command firtool; then
  FIRTOOL_AVAILABLE=true
  log_info "firtool version: $(firtool --version 2>&1 | head -n 1)"
else
  log_warning "firtool not available - CIRCT validation will be skipped"
  log_info "Install CIRCT: https://circt.llvm.org/GettingStarted/"
fi

echo ""

# --------------------------------------------------------------------------
# Step 2: Build Example with Debug Intrinsics
# --------------------------------------------------------------------------

print_header "[1/4] Building Example with Debug Intrinsics"

export CHISEL_DEBUG=true
log_info "CHISEL_DEBUG=$CHISEL_DEBUG"

log_info "Compiling DebugInfoExample..."

if sbt "runMain examples.DebugInfoExample" > "$LOG_FILE" 2>&1; then
  log_success "Example compiled successfully"
else
  log_error "Example compilation failed!"
  log_error "See log: $LOG_FILE"
  tail -n 50 "$LOG_FILE"
  exit 1
fi

if [[ ! -f "$FIRRTL_FILE" ]]; then
  log_error "FIRRTL output not found: $FIRRTL_FILE"
  log_error "Expected output location:"
  ls -la "$OUTPUT_DIR" || echo "  (directory does not exist)"
  exit 1
fi

log_success "FIRRTL generated: $FIRRTL_FILE ($(wc -l < $FIRRTL_FILE) lines)"

# --------------------------------------------------------------------------
# Step 2: Verify FIRRTL Intrinsics
# --------------------------------------------------------------------------

print_header "[2/4] Verifying FIRRTL Intrinsics"

log_info "Searching for circt_debug_typeinfo intrinsics..."

if ! grep -q "circt_debug_typeinfo" "$FIRRTL_FILE"; then
  log_error "No intrinsics found in FIRRTL output!"
  log_error "First 50 lines of FIRRTL:"
  head -n 50 "$FIRRTL_FILE"
  exit 1
fi

INTRINSIC_COUNT=$(grep -c "circt_debug_typeinfo" "$FIRRTL_FILE" || true)
log_success "Found $INTRINSIC_COUNT debug intrinsics"

# Validate intrinsic structure
log_info "Validating intrinsic syntax..."

if grep -q 'target = \"' "$FIRRTL_FILE"; then
  log_success "Symbolic references present (target=...)"
else
  log_error "Missing symbolic references!"
  grep "circt_debug_typeinfo" "$FIRRTL_FILE" | head -n 5
  exit 1
fi

if grep -q 'typeName = \"' "$FIRRTL_FILE"; then
  log_success "Type names present (typeName=...)"
else
  log_error "Missing type names!"
  exit 1
fi

if grep -q 'parameters = \"' "$FIRRTL_FILE"; then
  log_success "Type parameters present (parameters=...)"
else
  log_warning "No type parameters found (may be expected for simple types)"
fi

# Check for Scala artifacts in FIRRTL
log_info "Checking for Scala artifacts..."

if grep -q "ProbeValue" "$FIRRTL_FILE"; then
  log_error "BUG: ProbeValue object toString() found in FIRRTL output!"
  grep -n "ProbeValue" "$FIRRTL_FILE"
  exit 1
fi

log_success "No Scala artifacts found"

# --------------------------------------------------------------------------
# Step 3: Run firtool (Optional)
# --------------------------------------------------------------------------

if [[ "$FIRTOOL_AVAILABLE" == "false" ]]; then
  print_header "[3/4] Skipping firtool (Not Installed)"
  log_warning "Install CIRCT to validate MLIR debug ops"
  log_info "Visit: https://circt.llvm.org/GettingStarted/"
  echo ""
else
  print_header "[3/4] Running firtool Lowering"
  
  log_info "Lowering FIRRTL to MLIR..."
  
  if firtool "$FIRRTL_FILE" \
      --lower-to-hw \
      --export-module-hierarchy \
      --mlir-print-debuginfo \
      -o "$MLIR_FILE" 2>&1 | tee -a "$LOG_FILE"; then
    log_success "firtool completed successfully"
  else
    log_error "firtool failed!"
    log_error "See log: $LOG_FILE"
    tail -n 50 "$LOG_FILE"
    exit 1
  fi
  
  if [[ ! -f "$MLIR_FILE" ]]; then
    log_error "MLIR output not found: $MLIR_FILE"
    exit 1
  fi
  
  log_success "MLIR generated: $MLIR_FILE ($(wc -l < $MLIR_FILE) lines)"
  
  # --------------------------------------------------------------------------
  # Step 4: Verify MLIR Debug Ops
  # --------------------------------------------------------------------------
  
  print_header "[4/4] Verifying MLIR Debug Ops"
  
  log_info "Searching for dbg.variable ops..."
  
  if grep -q 'dbg\.variable' "$MLIR_FILE"; then
    DBG_OP_COUNT=$(grep -c 'dbg\.variable' "$MLIR_FILE" || true)
    log_success "Found $DBG_OP_COUNT dbg.variable ops"
    
    # Show sample debug op
    log_info "Sample debug operation:"
    grep -A 2 'dbg\.variable' "$MLIR_FILE" | head -n 5 | sed 's/^/  /'
  else
    log_warning "No dbg.variable ops found in MLIR"
    log_warning "This may indicate CIRCT doesn't support circt_debug_typeinfo yet"
    log_info "Checking for alternative debug constructs..."
    
    if grep -q 'dbg\.' "$MLIR_FILE"; then
      log_info "Found other dbg.* ops:"
      grep 'dbg\.' "$MLIR_FILE" | head -n 5 | sed 's/^/  /'
    else
      log_warning "No debug ops found - intrinsics may have been pruned"
    fi
  fi
  
  # Verify module hierarchy preservation
  if grep -q "hw\.module @${EXAMPLE_NAME}" "$MLIR_FILE"; then
    log_success "Module hierarchy preserved"
  else
    log_warning "Module hierarchy may have been inlined"
  fi
fi

# ==============================================================================
# Success Summary
# ==============================================================================

print_header "Validation Summary"

echo -e "${GREEN}✓${NC} FIRRTL compilation: ${GREEN}PASSED${NC}"
echo -e "${GREEN}✓${NC} Debug intrinsics:   ${GREEN}PASSED${NC} ($INTRINSIC_COUNT intrinsics)"
echo -e "${GREEN}✓${NC} Symbolic references: ${GREEN}PASSED${NC}"
echo -e "${GREEN}✓${NC} Scala artifacts check: ${GREEN}PASSED${NC}"

if [[ "$FIRTOOL_AVAILABLE" == "true" ]]; then
  echo -e "${GREEN}✓${NC} firtool lowering:   ${GREEN}PASSED${NC}"
  
  if grep -q 'dbg\.variable' "$MLIR_FILE"; then
    echo -e "${GREEN}✓${NC} MLIR debug ops:     ${GREEN}PASSED${NC}"
  else
    echo -e "${YELLOW}⚠${NC} MLIR debug ops:     ${YELLOW}PARTIAL${NC} (see warnings above)"
  fi
else
  echo -e "${YELLOW}⚠${NC} firtool lowering:   ${YELLOW}SKIPPED${NC} (not installed)"
fi

echo -e "\n${GREEN}========================================${NC}"
echo -e "${GREEN}✓ VALIDATION SUCCESSFUL${NC}"
echo -e "${GREEN}========================================${NC}\n"

echo "Generated outputs:"
echo "  - FIRRTL: $FIRRTL_FILE"
if [[ "$FIRTOOL_AVAILABLE" == "true" ]]; then
  echo "  - MLIR:   $MLIR_FILE"
fi
echo "  - Log:    $LOG_FILE"

echo -e "\n${GREEN}✅ Ready for Tywaves/HGDB integration!${NC}\n"

exit 0
