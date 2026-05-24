#!/usr/bin/env python3
"""
Order Service stub — simulates the Order Service calling POST /wallets/:id/deduct.

Usage:
    python order_stub.py <wallet_id> [--orders N] [--concurrent]

Examples:
    # Place 3 sequential orders
    python order_stub.py wallet-abc-123 --orders 3

    # Simulate 5 concurrent order placements (race condition test)
    python order_stub.py wallet-abc-123 --orders 5 --concurrent

    # Test idempotency: same order ID sent twice
    python order_stub.py wallet-abc-123 --idempotency-test
"""

import argparse
import sys
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed

try:
    import requests
except ImportError:
    print("Install requests: pip install requests")
    sys.exit(1)

BASE_URL = "http://localhost:8080"


def place_order(wallet_id: str, order_id: str) -> dict:
    """Calls the Wallet Service to deduct ₹100 for one order."""
    resp = requests.post(
        f"{BASE_URL}/wallets/{wallet_id}/deduct",
        json={"referenceId": order_id},
        headers={
            "Content-Type": "application/json",
            "Idempotency-Key": order_id,
        },
        timeout=10,
    )
    return {
        "order_id":   order_id,
        "http_status": resp.status_code,
        "body":        resp.json(),
    }


def get_balance(wallet_id: str) -> dict:
    resp = requests.get(f"{BASE_URL}/wallets/{wallet_id}/balance", timeout=5)
    return resp.json()


def run_sequential(wallet_id: str, n: int):
    print(f"\n--- Sequential: placing {n} orders on wallet {wallet_id} ---")
    for i in range(n):
        order_id = f"ORDER-SEQ-{uuid.uuid4()}"
        result = place_order(wallet_id, order_id)
        status = "OK" if result["http_status"] == 200 else "FAIL"
        balance = result["body"].get("data", {}).get("balanceAfterInr", "N/A")
        print(f"  [{status}] {order_id} — balance after: ₹{balance}")
    print_balance(wallet_id)


def run_concurrent(wallet_id: str, n: int):
    print(f"\n--- Concurrent: firing {n} simultaneous deductions on wallet {wallet_id} ---")
    order_ids = [f"ORDER-CONC-{uuid.uuid4()}" for _ in range(n)]

    with ThreadPoolExecutor(max_workers=n) as pool:
        futures = {pool.submit(place_order, wallet_id, oid): oid for oid in order_ids}
        for future in as_completed(futures):
            result = future.result()
            status = "OK  " if result["http_status"] == 200 else "FAIL"
            error = result["body"].get("error", "")
            balance = result["body"].get("data", {}).get("balanceAfterInr", "—")
            print(f"  [{status}] {result['order_id']}  balance_after=₹{balance}  {error}")

    print_balance(wallet_id)


def run_idempotency_test(wallet_id: str):
    print(f"\n--- Idempotency test: same order ID sent twice ---")
    order_id = f"ORDER-IDEM-{uuid.uuid4()}"

    r1 = place_order(wallet_id, order_id)
    r2 = place_order(wallet_id, order_id)  # identical key

    txn1 = r1["body"].get("data", {}).get("transactionId")
    txn2 = r2["body"].get("data", {}).get("transactionId")

    print(f"  Call 1 → transactionId={txn1}  status={r1['http_status']}")
    print(f"  Call 2 → transactionId={txn2}  status={r2['http_status']}")

    if txn1 and txn1 == txn2:
        print("  PASS: both calls returned the same transaction (idempotent)")
    else:
        print("  FAIL: transaction IDs differ — wallet was charged twice!")

    print_balance(wallet_id)


def print_balance(wallet_id: str):
    bal = get_balance(wallet_id)
    data = bal.get("data", {})
    print(f"\n  Current balance: ₹{data.get('balanceInr', '?')} ({data.get('balancePaise', '?')} paise)")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Keychain Order Service stub")
    parser.add_argument("wallet_id", help="Target wallet ID")
    parser.add_argument("--orders", type=int, default=1, help="Number of orders to place")
    parser.add_argument("--concurrent", action="store_true", help="Fire all orders simultaneously")
    parser.add_argument("--idempotency-test", action="store_true", help="Run idempotency test")
    args = parser.parse_args()

    if args.idempotency_test:
        run_idempotency_test(args.wallet_id)
    elif args.concurrent:
        run_concurrent(args.wallet_id, args.orders)
    else:
        run_sequential(args.wallet_id, args.orders)
