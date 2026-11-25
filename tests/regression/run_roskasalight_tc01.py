"""
Automated Android regression test runner for RosKasaLight16 (Delphi FMX) via Autopilot.
Reads test parameters from test_dataset.json, drives the live Android app over adb forward,
and logs created RACUN_IDs for SQL verification (02_snapshot_and_compare.sql).
"""
import json
import sys
import time
from pathlib import Path

from autopilot_client import AutopilotClient, ensure_adb_forward

DATASET_PATH = Path(__file__).parent / "test_dataset.json"


def load_dataset() -> dict:
    return json.loads(DATASET_PATH.read_text(encoding="utf-8"))


def get_state(ap: AutopilotClient) -> dict:
    return ap.call("call_method", name="GetState")["result"]


def wait_for_tab(ap: AutopilotClient, tab_name: str, timeout: float = 10.0) -> dict:
    deadline = time.time() + timeout
    st = {}
    while time.time() < deadline:
        st = get_state(ap)
        if st.get("activeTab") == tab_name:
            return st
        time.sleep(0.4)
    raise TimeoutError(f"Timed out waiting for tab {tab_name}; last state={st}")


def login_pin(ap: AutopilotClient, pin: str):
    forms = ap.call("list_tree", formsOnly=True)["components"]
    if any(f["name"] == "frmNFClogin" and f.get("visible") for f in forms):
        ap.call("click", path="frmNFClogin.btnPIN")
        time.sleep(0.5)
    st = get_state(ap)
    if st.get("activeTab") == "TabLogin":
        for digit in pin:
            ap.call("click", path=f"frmKasaMobile.btn{digit}")
        ap.call("click", path="frmKasaMobile.btnOK")
        wait_for_tab(ap, "TabMize", timeout=15.0)
        time.sleep(2.0)  # wait for async getMize / getRacuniSeznam to populate GPmize


def find_payment_button(ap: AutopilotClient, payment_name: str) -> str:
    comps = ap.call("list_tree", path="frmKasaMobile.GridPanelPlacila")["components"]
    target = payment_name.strip().upper()
    for c in comps:
        if c.get("text", "").strip().upper() == target:
            return c["path"]
    raise RuntimeError(f"Payment button '{payment_name}' not found in GridPanelPlacila")


def run_tc01_simple_gotovina(ap: AutopilotClient, ds: dict) -> dict:
    print("=== TC01_SIMPLE_GOTOVINA ===")
    pin = ds["environment"]["pin"]
    marker = ds["tables"]["primary"]
    art = ds["articles"]["pivo_lasko"]
    pay = ds["payments"]["gotovina"]

    login_pin(ap, pin)
    print(f"  [1] Logged in with PIN {pin}")

    ap.call("call_method", name="TapMiza", args={"marker": marker})
    wait_for_tab(ap, "TabCenik", timeout=10.0)
    print(f"  [2] Opened {marker}")

    ap.call("call_method", name="OrderArticle", args={"nivo4": art["nivo4_id"], "naziv": art["db_name"]})
    time.sleep(0.8)
    st = get_state(ap)
    print(f"  [3] Ordered {art['db_name']} -> pozicCount={st.get('pozicCount')}")

    # POST order to server
    ap.call("click", path="frmKasaMobile.btn1naroci")
    time.sleep(2.5)
    st = get_state(ap)
    racun_id = st.get("racunId")
    print(f"  [4] POSTed order -> racunId={racun_id}, activeTab={st.get('activeTab')}")

    # If POST returned to TabMize, re-open the table
    if st.get("activeTab") == "TabMize":
        ap.call("call_method", name="TapMiza", args={"marker": marker})
        wait_for_tab(ap, "TabCenik", timeout=10.0)
        st = get_state(ap)
        racun_id = st.get("racunId")
        print(f"  [4b] Re-opened {marker} -> racunId={racun_id}")

    # Open Placila tab
    ap.call("click", path="frmKasaMobile.btn1placila")
    wait_for_tab(ap, "TabRacPlaci", timeout=10.0)
    pay_btn = find_payment_button(ap, pay["name"])
    ap.call("click", path=pay_btn)
    time.sleep(1.0)
    st = get_state(ap)
    print(f"  [5] Added payment {pay['name']} ({pay_btn}) -> placiCount={st.get('placiCount')}")

    # Print receipt (Izpis racuna)
    ap.call("click", path="frmKasaMobile.btnIzpisRacuna")
    time.sleep(4.0)
    st_after = get_state(ap)
    forms_after = [(f["name"], f.get("visible")) for f in ap.call("list_tree", formsOnly=True)["components"]]
    print(f"  [6] Printed receipt -> state={st_after}, forms={forms_after}")

    return {"scenario": "TC01_SIMPLE_GOTOVINA", "racun_id": racun_id, "marker": marker}


if __name__ == "__main__":
    ds = load_dataset()
    pid = ensure_adb_forward()
    print(f"Connected to RosKasaLight on Android (PID={pid})")
    with AutopilotClient() as ap:
        ap.call("set_keep_awake", enabled=True)
        res = run_tc01_simple_gotovina(ap, ds)
        print("RESULT:", json.dumps(res, ensure_ascii=False))
