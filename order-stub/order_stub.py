#!/usr/bin/env python3
"""
Order Service stub — simulates the Order Service calling POST /wallets/:id/deduct.

Usage:
    python3 order_stub.py --full-demo                          # end-to-end demo (creates wallet, tops up, deducts, shows ledger)
    python3 order_stub.py <wallet_id> --orders 3               # sequential deductions
    python3 order_stub.py <wallet_id> --orders 5 --concurrent  # concurrent race-condition proof
    python3 order_stub.py <wallet_id> --idempotency-test       # same order ID sent twice
"""

import argparse
import sys
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed

try:
    import requests
except ImportError:
    print("Install dependencies: pip3 install -r requirements.txt")
    sys.exit(1)

BASE_URL = "http://localhost:8080"
WARN = "\033[33m"
OK   = "\033[32m"
FAIL = "\033[31m"
RESET= "\033[0m"


# ---------------------------------------------------------------------------
# Auth
# ---------------------------------------------------------------------------

def fetch_token(customer_id: str, role: str) -> str:
    resp = requests.post(f"{BASE_URL}/auth/token",
                         json={"customerId": customer_id, "role": role}, timeout=5)
    resp.raise_for_status()
    return resp.json()["token"]


# ---------------------------------------------------------------------------
# Wallet management
# ---------------------------------------------------------------------------

def create_wallet(admin_token: str, cust_id: str) -> str:
    """Creates a fresh wallet and returns its walletId."""
    resp = requests.post(
        f"{BASE_URL}/wallets",
        json={"customerId": cust_id, "name": "Demo Customer",
              "email": "demo@keychain.com", "phone": "+919876543210"},
        headers={"Authorization": f"Bearer {admin_token}"},
        timeout=10,
    )
    resp.raise_for_status()
    wallet_id = resp.json()["data"]["walletId"]
    print(f"  Wallet created  → walletId={wallet_id}  customerId={cust_id}")
    return wallet_id


def topup(wallet_id: str, amount: float, token: str) -> float:
    """Tops up the wallet and returns the new balance in INR."""
    resp = requests.post(
        f"{BASE_URL}/wallets/{wallet_id}/topup",
        json={"amount": amount},
        headers={"Authorization": f"Bearer {token}"},
        timeout=10,
    )
    resp.raise_for_status()
    balance_after = resp.json()["data"]["balanceAfterInr"]
    print(f"  Topped up ₹{amount:.0f}  → balance=₹{balance_after}")
    return balance_after


def get_balance(wallet_id: str, token: str) -> dict:
    resp = requests.get(
        f"{BASE_URL}/wallets/{wallet_id}/balance",
        headers={"Authorization": f"Bearer {token}"},
        timeout=5,
    )
    return resp.json().get("data", {})


def get_transactions(wallet_id: str, token: str) -> list:
    resp = requests.get(
        f"{BASE_URL}/wallets/{wallet_id}/transactions",
        headers={"Authorization": f"Bearer {token}"},
        timeout=5,
    )
    return resp.json().get("data", [])


# ---------------------------------------------------------------------------
# Order Service operations
# ---------------------------------------------------------------------------

def place_order(wallet_id: str, order_id: str, token: str) -> dict:
    """Calls the Wallet Service to deduct ₹100 for one order."""
    resp = requests.post(
        f"{BASE_URL}/wallets/{wallet_id}/deduct",
        json={"referenceId": order_id},
        headers={
            "Authorization":   f"Bearer {token}",
            "Content-Type":    "application/json",
            "Idempotency-Key": order_id,
        },
        timeout=10,
    )
    return {"order_id": order_id, "http_status": resp.status_code, "body": resp.json()}


# ---------------------------------------------------------------------------
# Demo modes
# ---------------------------------------------------------------------------

def print_balance(wallet_id: str, token: str):
    data = get_balance(wallet_id, token)
    print(f"\n  Balance → ₹{data.get('balanceInr', '?')} ({data.get('balancePaise', '?')} paise)")


def print_ledger(wallet_id: str, token: str):
    txns = get_transactions(wallet_id, token)
    print(f"\n  {'TYPE':<10} {'AMOUNT (₹)':<14} {'BALANCE AFTER (₹)':<20} REFERENCE")
    print("  " + "-" * 65)
    for t in txns:
        print(f"  {t.get('type',''):<10} {t.get('amountInr',0):<14} {t.get('balanceAfterInr',0):<20} {t.get('referenceId') or '—'}")


def run_sequential(wallet_id: str, n: int, token: str):
    print(f"\n--- Sequential: {n} orders on wallet {wallet_id} ---")
    for _ in range(n):
        order_id = f"ORDER-SEQ-{uuid.uuid4()}"
        result = place_order(wallet_id, order_id, token)
        if result["http_status"] == 200:
            bal = result["body"]["data"]["balanceAfterInr"]
            print(f"  {OK}[OK]{RESET}   {order_id}  balance_after=₹{bal}")
        else:
            err = result["body"].get("error", "")
            print(f"  {FAIL}[FAIL]{RESET} {order_id}  {err}")
    print_balance(wallet_id, token)


def run_concurrent(wallet_id: str, n: int, token: str):
    print(f"\n--- Concurrent: {n} simultaneous deductions on wallet {wallet_id} ---")
    order_ids = [f"ORDER-CONC-{uuid.uuid4()}" for _ in range(n)]
    with ThreadPoolExecutor(max_workers=n) as pool:
        futures = {pool.submit(place_order, wallet_id, oid, token): oid for oid in order_ids}
        for future in as_completed(futures):
            result = future.result()
            if result["http_status"] == 200:
                bal = result["body"]["data"]["balanceAfterInr"]
                print(f"  {OK}[OK]{RESET}   {result['order_id']}  balance_after=₹{bal}")
            else:
                err = result["body"].get("error", "")
                print(f"  {FAIL}[FAIL]{RESET} {result['order_id']}  {err}")
    print_balance(wallet_id, token)


def run_idempotency_test(wallet_id: str, token: str):
    print(f"\n--- Idempotency: same order ID sent twice ---")
    order_id = f"ORDER-IDEM-{uuid.uuid4()}"

    r1 = place_order(wallet_id, order_id, token)
    r2 = place_order(wallet_id, order_id, token)  # identical key

    txn1 = r1["body"].get("data", {}).get("transactionId")
    txn2 = r2["body"].get("data", {}).get("transactionId")

    print(f"  Call 1 → transactionId={txn1}  http={r1['http_status']}")
    print(f"  Call 2 → transactionId={txn2}  http={r2['http_status']}")

    if txn1 and txn1 == txn2:
        print(f"  {OK}PASS{RESET}: same transactionId returned — wallet charged exactly once")
    else:
        print(f"  {FAIL}FAIL{RESET}: transaction IDs differ — double charge!")

    print_balance(wallet_id, token)


def run_full_demo():
    """
    Complete end-to-end demo:
      1. Create wallet
      2. Customer tops up ₹500
      3. Order Service places 3 sequential orders (drains to ₹200)
      4. Idempotency proof — same order ID twice
      5. Insufficient balance proof — wallet has ₹200, attempt 3 more orders
      6. Print full transaction ledger
    """
    print("\n" + "=" * 60)
    print("  Keychain Wallet — Full Demo")
    print("=" * 60)

    # Tokens — cust_id is shared between wallet creation and CUSTOMER JWT
    print("\n[1] Fetching tokens...")
    cust_id       = f"CUST-{uuid.uuid4().hex[:6].upper()}"
    admin_token   = fetch_token("ADMIN-DEMO",    "ADMIN")
    service_token = fetch_token("ORDER-SERVICE", "SERVICE")
    cust_token    = fetch_token(cust_id,         "CUSTOMER")
    print(f"  ADMIN token    → {admin_token[:40]}...")
    print(f"  SERVICE token  → {service_token[:40]}...")
    print(f"  CUSTOMER token → {cust_token[:40]}...")

    # Create wallet with the same cust_id so the CUSTOMER token passes ownership check
    print("\n[2] Creating wallet (ADMIN only)...")
    wallet_id = create_wallet(admin_token, cust_id)

    # Customer tops up (uses ADMIN token here; CUSTOMER token also works via ownership check)
    print("\n[3] Customer tops up ₹500...")
    topup(wallet_id, 500.00, admin_token)

    # Sequential orders
    print("\n[4] Order Service places 3 orders (sequential)...")
    run_sequential(wallet_id, 3, service_token)

    # Idempotency
    print("\n[5] Idempotency proof — Order Service retries same order...")
    run_idempotency_test(wallet_id, service_token)

    # Insufficient balance (wallet has ₹200 left — only 2 more deductions possible)
    print("\n[6] Insufficient balance proof — attempt 3 orders with ₹200 left...")
    run_concurrent(wallet_id, 3, service_token)

    # Full ledger
    print("\n[7] Full transaction ledger...")
    print_ledger(wallet_id, cust_token)

    print("\n" + "=" * 60)
    print("  Demo complete.")
    print("=" * 60 + "\n")


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Keychain Order Service stub")
    parser.add_argument("wallet_id",          nargs="?",          help="Target wallet ID (not needed for --full-demo)")
    parser.add_argument("--orders",           type=int, default=1, help="Number of orders to place")
    parser.add_argument("--concurrent",       action="store_true", help="Fire all orders simultaneously")
    parser.add_argument("--idempotency-test", action="store_true", help="Send same order ID twice")
    parser.add_argument("--topup",            type=float,          help="Top up the wallet by this amount (INR) before running")
    parser.add_argument("--ledger",           action="store_true", help="Print full transaction history after run")
    parser.add_argument("--full-demo",        action="store_true", help="Run the complete end-to-end demo (no wallet_id needed)")
    args = parser.parse_args()

    if args.full_demo:
        try:
            run_full_demo()
        except requests.exceptions.ConnectionError:
            print("ERROR: Cannot reach http://localhost:8080 — is the wallet service running?")
            sys.exit(1)
        sys.exit(0)

    if not args.wallet_id:
        parser.error("wallet_id is required unless --full-demo is used")

    print("Fetching SERVICE token...")
    try:
        service_token = fetch_token("ORDER-SERVICE", "SERVICE")
        admin_token   = fetch_token("ADMIN-DEMO",    "ADMIN")
        print(f"Token acquired: {service_token[:40]}...")
    except Exception as e:
        print(f"ERROR: Could not fetch token — is the wallet service running? ({e})")
        sys.exit(1)

    if args.topup:
        print(f"\nTopping up ₹{args.topup:.0f}...")
        topup(args.wallet_id, args.topup, admin_token)

    if args.idempotency_test:
        run_idempotency_test(args.wallet_id, service_token)
    elif args.concurrent:
        run_concurrent(args.wallet_id, args.orders, service_token)
    else:
        run_sequential(args.wallet_id, args.orders, service_token)

    if args.ledger:
        print("\nTransaction ledger:")
        print_ledger(args.wallet_id, admin_token)
