"""
Full automated Android regression suite (TC01-TC10) for RosKasaLight16 (Delphi FMX).
Reads test_dataset.json, drives the live Android app on device/emulator via Autopilot,
and prints a structured summary of all created RACUN_IDs for Oracle SQL verification.

Usage:
  python run_roskasalight_suite.py              # run all TC01-TC10
  python run_roskasalight_suite.py TC02 TC03    # run specific scenarios
"""
import json
import sys
import time
from pathlib import Path

from autopilot_client import AutopilotClient, ensure_adb_forward

DATASET_PATH = Path(__file__).parent / "test_dataset.json"
RESULTS_DIR = Path(__file__).parent / "results"


def load_dataset() -> dict:
    return json.loads(DATASET_PATH.read_text(encoding="utf-8"))


def get_state(ap: AutopilotClient) -> dict:
    return ap.call("call_method", name="GetState")["result"]


def wait_for(ap: AutopilotClient, predicate, desc: str, timeout: float = 12.0, poll: float = 0.4) -> dict:
    deadline = time.time() + timeout
    st = {}
    while time.time() < deadline:
        st = get_state(ap)
        if predicate(st):
            return st
        time.sleep(poll)
    raise TimeoutError(f"Timeout waiting for {desc}; last state={st}")


def go_to_mize(ap: AutopilotClient):
    st = get_state(ap)
    tab = st.get("activeTab")
    if tab == "TabMize":
        return
    if tab == "TabCenik":
        ap.call("click", path="frmKasaMobile.btn1mize")
    elif tab == "TabRacPlaci":
        ap.call("click", path="frmKasaMobile.btnPlacMize")
    elif tab == "TabRacGlava":
        ap.call("click", path="frmKasaMobile.btnRacMize")
    wait_for(ap, lambda s: s.get("activeTab") == "TabMize", "TabMize")
    time.sleep(1.0)


def login_pin(ap: AutopilotClient, pin: str):
    forms = ap.call("list_tree", formsOnly=True)["components"]
    if any(f["name"] == "frmNFClogin" and f.get("visible") for f in forms):
        ap.call("click", path="frmNFClogin.btnPIN")
        time.sleep(0.5)
    st = get_state(ap)
    if st.get("activeTab") == "TabLogin":
        # Wait until PIN keypad is enabled (Win32 startup initializes for ~5s)
        first_btn = f"frmKasaMobile.btn{pin[0]}"
        deadline = time.time() + 15.0
        while time.time() < deadline:
            try:
                if ap.call("read_property", path=first_btn, propname="Enabled").get("value") is True:
                    break
            except Exception:
                pass
            time.sleep(0.5)
        for d in pin:
            ap.call("click", path=f"frmKasaMobile.btn{d}")
        ap.call("click", path="frmKasaMobile.btnOK")
        wait_for(ap, lambda s: s.get("activeTab") == "TabMize", "TabMize after login", timeout=15.0)
        time.sleep(2.0)


def open_table(ap: AutopilotClient, marker: str):
    go_to_mize(ap)
    ap.call("call_method", name="TapMiza", args={"marker": marker})
    wait_for(ap, lambda s: s.get("activeTab") == "TabCenik", f"TabCenik for {marker}")
    time.sleep(0.5)


def order_article(ap: AutopilotClient, art: dict, qty: int = 1, ep_button: str | None = None):
    for _ in range(qty):
        ap.call("call_method", name="OrderArticle", args={"nivo4": art["nivo4_id"], "naziv": art["db_name"]})
        time.sleep(0.5)
        st = get_state(ap)
        if st.get("layoutEPVisible"):
            btn = ep_button or "frmKasaMobile.btnEp7"  # 0.25 default for lestvica
            ap.call("click", path=btn)
            time.sleep(0.5)


def post_order(ap: AutopilotClient, marker: str) -> int:
    ap.call("click", path="frmKasaMobile.btn1naroci")
    time.sleep(2.5)
    st = get_state(ap)
    if st.get("activeTab") == "TabMize":
        open_table(ap, marker)
        st = get_state(ap)
    if int(st.get("racunId") or 0) <= 0:
        st = wait_for(ap, lambda s: int(s.get("racunId") or 0) > 0, "positive racunId after POST", timeout=10.0)
    return int(st.get("racunId") or 0)


def find_payment_button(ap: AutopilotClient, payment_name: str, placilo_id: int | None = None) -> str:
    if placilo_id is not None:
        try:
            res = ap.call("call_method", name="FindPaymentPath", args={"placiloId": placilo_id})
            path = res.get("result", {}).get("path")
            if path:
                return path
        except Exception:
            pass
    comps = ap.call("list_tree", path="frmKasaMobile.GridPanelPlacila")["components"]
    target = payment_name.strip().upper()
    for c in comps:
        if c.get("text", "").strip().upper() == target:
            return c["path"]
    raise RuntimeError(f"Payment button '{payment_name}' (id={placilo_id}) not found")


def open_placila(ap: AutopilotClient):
    ap.call("click", path="frmKasaMobile.btn1placila")
    wait_for(ap, lambda s: s.get("activeTab") == "TabRacPlaci", "TabRacPlaci")
    time.sleep(0.5)


def add_simple_payment(ap: AutopilotClient, payment_name: str, amount: str | None = None, placilo_id: int | None = None):
    btn = find_payment_button(ap, payment_name, placilo_id)
    ap.call("click", path=btn)
    time.sleep(0.8)
    args = {"value": amount} if amount else {}
    ap.call("call_method", name="ConfirmNumDlg", args=args)
    time.sleep(1.5)


def print_receipt(ap: AutopilotClient):
    ap.call("click", path="frmKasaMobile.btnIzpisRacuna")
    time.sleep(4.0)


def tc01_simple_gotovina(ap: AutopilotClient, ds: dict, ctx: dict) -> dict:
    marker = ds["tables"]["primary"]
    open_table(ap, marker)
    order_article(ap, ds["articles"]["pivo_lasko"], qty=1)
    rid = post_order(ap, marker)
    open_placila(ap)
    pay = ds["payments"]["gotovina"]
    add_simple_payment(ap, pay["name"], placilo_id=pay["placilo_id"])
    print_receipt(ap)
    ctx["TC01_racun_id"] = rid
    return {"scenario": "TC01_SIMPLE_GOTOVINA", "racun_id": rid, "status": "DONE"}


def tc02_paket_kred_rocno(ap: AutopilotClient, ds: dict, ctx: dict) -> dict:
    marker = ds["tables"]["primary"]
    open_table(ap, marker)
    order_article(ap, ds["articles"]["paket_kremna_rezina"], qty=1)
    rid = post_order(ap, marker)
    open_placila(ap)
    pay = ds["payments"]["kred_k_rocno"]
    if ctx.get("win32"):
        try:
            add_simple_payment(ap, pay["name"], placilo_id=pay["placilo_id"])
        except Exception:
            # Win32 with KKARTICEVPLACILIH=D hides individual card buttons from GridPanelPlacila;
            # invoke btnPlac1Click directly with Tag=placilo_id (5 = KRED. K ROCNO) and confirm frmNumDlg
            ap.call("call_method", name="KlikniPlaciloCmd", args={"placiloId": pay["placilo_id"]})
            time.sleep(0.8)
            ap.call("call_method", name="ConfirmNumDlg", args={})
            wait_for(ap, lambda s: (s.get("placiCount") or 0) > 0, "payment added via KlikniPlaciloCmd", timeout=10.0)
    else:
        add_simple_payment(ap, pay["name"], placilo_id=pay["placilo_id"])
    print_receipt(ap)
    ctx["TC02_racun_id"] = rid
    return {"scenario": "TC02_PAKET_KRED_ROCNO", "racun_id": rid, "status": "DONE"}


def tc03_lestvica_gost_hotela(ap: AutopilotClient, ds: dict, ctx: dict) -> dict:
    marker = ds["tables"]["primary"]
    open_table(ap, marker)
    order_article(ap, ds["articles"]["pijaca_lestvica_belo"], qty=1, ep_button="frmKasaMobile.btnEp7")
    rid = post_order(ap, marker)
    open_placila(ap)
    pay = ds["payments"]["gost_hotela"]
    btn = find_payment_button(ap, pay["name"], placilo_id=pay["placilo_id"])
    click_args = {"path": btn, "mode": "message"} if ctx.get("win32") else {"path": btn}
    ap.call("click", **click_args)
    wait_for(ap, lambda s: s.get("sobeVisible"), "frmSobe visible", timeout=10.0)
    guest = ds["hotel_guest"]
    ap.call("call_method", name="HotelSearch", args={"search": guest["room"]})
    wait_for(ap, lambda s: (s.get("sobeCount") or 0) > 0, "LV_sobe populated", timeout=10.0)
    ap.call("call_method", name="HotelPick", args={"match": guest["guest_search"]})
    time.sleep(2.0)
    print_receipt(ap)
    return {"scenario": "TC03_LESTVICA_GOST_HOTELA", "racun_id": rid, "status": "DONE"}


def _run_dobavnica(ap: AutopilotClient, ds: dict, scenario_id: str, article_key: str, partner_key: str) -> dict:
    marker = ds["tables"]["primary"]
    partner = ds["partners"][partner_key]
    open_table(ap, marker)
    order_article(ap, ds["articles"][article_key], qty=1)
    rid = post_order(ap, marker)
    open_placila(ap)
    btn = find_payment_button(ap, ds["payments"]["dobavnica"]["name"])
    ap.call("click", path=btn)
    wait_for(ap, lambda s: s.get("partnerVisible"), "frmPartner visible", timeout=10.0)
    ap.call("call_method", name="PartnerSearch", args={"search": partner["search_text"]})
    wait_for(ap, lambda s: (s.get("partnerCount") or 0) > 0, "lvPartnerji populated", timeout=10.0)
    ap.call("call_method", name="PartnerPick", args={"partnerId": partner["partner_id"]})
    time.sleep(2.0)
    print_receipt(ap)
    return {"scenario": scenario_id, "racun_id": rid, "partner_id": partner["partner_id"], "status": "DONE"}


def tc04_dobavnica_ros(ap: AutopilotClient, ds: dict, ctx: dict) -> dict:
    return _run_dobavnica(ap, ds, "TC04_DOBAVNICA_ROS", "pivo_guinnes", "ros")


def tc05_dobavnica_prva(ap: AutopilotClient, ds: dict, ctx: dict) -> dict:
    return _run_dobavnica(ap, ds, "TC05_DOBAVNICA_PRVA_OSEBNA_ZAVAROVALNICA", "pivo_lasko", "prva_osebna_zavarovalnica")


def tc06_popust_99(ap: AutopilotClient, ds: dict, ctx: dict) -> dict:
    marker = ds["tables"]["primary"]
    open_table(ap, marker)
    order_article(ap, ds["articles"]["pivo_lasko"], qty=2)
    order_article(ap, ds["articles"]["pivo_guinnes"], qty=1)
    rid = post_order(ap, marker)
    open_placila(ap)
    ap.call("click", path="frmKasaMobile.btnPopust")
    time.sleep(0.8)
    ap.call("call_method", name="ConfirmNumDlg", args={"value": "10"})
    time.sleep(1.5)
    add_simple_payment(ap, ds["payments"]["gotovina"]["name"])
    print_receipt(ap)
    return {"scenario": "TC06_POPUST_99_CELOTEN_RACUN", "racun_id": rid, "status": "DONE"}


def tc07_popust_1_pozicija(ap: AutopilotClient, ds: dict, ctx: dict) -> dict:
    marker = ds["tables"]["primary"]
    open_table(ap, marker)
    order_article(ap, ds["articles"]["pivo_lasko"], qty=1)
    if ctx.get("win32"):
        ap.call("call_method", name="OpenLineEdit", args={"index": 0})
        time.sleep(1.0)
        ap.call("call_method", name="ConfirmLineDiscount", args={"percent": 10})
    else:
        ap.call("call_method", name="EditLineDiscount", args={"index": 0, "percent": 10})
    time.sleep(0.8)
    order_article(ap, ds["articles"]["pivo_guinnes"], qty=1)
    rid = post_order(ap, marker)
    open_placila(ap)
    pay = ds["payments"]["gotovina"]
    add_simple_payment(ap, pay["name"], placilo_id=pay["placilo_id"])
    print_receipt(ap)
    return {"scenario": "TC07_POPUST_1_POZICIJA", "racun_id": rid, "status": "DONE"}


def tc08_zdruzi_in_split(ap: AutopilotClient, ds: dict, ctx: dict) -> dict:
    m1 = ds["tables"]["primary"]
    m2 = ds["tables"]["secondary"]
    # Receipt A on Miza 58: Paket + 4x Pivo Lasko
    open_table(ap, m1)
    order_article(ap, ds["articles"]["paket_kremna_rezina"], qty=1)
    order_article(ap, ds["articles"]["pivo_lasko"], qty=4)
    rid_a = post_order(ap, m1)
    # Receipt B on Miza 59: 1x Pivo Guinnes
    open_table(ap, m2)
    order_article(ap, ds["articles"]["pivo_guinnes"], qty=1)
    rid_b = post_order(ap, m2)
    # Open Racuni tab and select Receipt A, then Zdruzi Receipt B into A
    go_to_mize(ap)
    ap.call("click", path="frmKasaMobile.btnMizeRacuni")
    wait_for(ap, lambda s: s.get("activeTab") == "TabRacGlava" and (s.get("racGlavaCount") or 0) > 0, "TabRacGlava")
    time.sleep(1.0)
    ap.call("call_method", name="SelectRacun", args={"racunId": rid_a})
    zdruzi_args = {"path": "frmKasaMobile.btnRacZdruziRacune", "mode": "message"} if ctx.get("win32") else {"path": "frmKasaMobile.btnRacZdruziRacune"}
    ap.call("click", **zdruzi_args)
    wait_for(ap, lambda s: s.get("zdruziVisible"), "frmZdruziRacune visible", timeout=10.0)
    ap.call("call_method", name="ZdruziExecute", args={"racunId": rid_b})
    time.sleep(1.0)
    try:
        ap.call("call_method", name="ConfirmMsgDlg", args={"ok": True})
    except Exception:
        pass
    time.sleep(3.0)
    # Split 2x Pivo Lasko from merged Receipt A onto new receipt
    wait_for(ap, lambda s: s.get("activeTab") == "TabRacGlava", "TabRacGlava after merge", timeout=10.0)
    time.sleep(1.0)
    ap.call("call_method", name="SelectRacun", args={"racunId": rid_a})
    split_args = {"path": "frmKasaMobile.btnSplit", "mode": "message"} if ctx.get("win32") else {"path": "frmKasaMobile.btnSplit"}
    ap.call("click", **split_args)
    wait_for(ap, lambda s: s.get("splitVisible"), "frmSplit visible", timeout=10.0)
    ap.call("call_method", name="SplitExecute", args={"nivo4": ds["articles"]["pivo_lasko"]["nivo4_id"], "count": 2})
    time.sleep(3.0)
    return {"scenario": "TC08_ZDRUZI_IN_SPLIT", "racun_a": rid_a, "racun_b": rid_b, "status": "DONE"}


def _run_storno(ap: AutopilotClient, scenario_id: str, target_rid: int, create_new: bool, win32: bool = False) -> dict:
    go_to_mize(ap)
    ap.call("click", path="frmKasaMobile.btnMizeRacuni")
    wait_for(ap, lambda s: s.get("activeTab") == "TabRacGlava", "TabRacGlava")
    time.sleep(1.5)
    toggle_btn = "frmKasaMobile.btnRacOdprtiIzpisani" if win32 else "frmKasaMobile.btnIzpisaniRacuni"
    if win32:
        cur_txt = str(ap.call("read_property", path=toggle_btn, propname="Text").get("value", ""))
        if cur_txt.lower() != "odprti":
            ap.call("click", path=toggle_btn)
            time.sleep(3.0)
    else:
        ap.call("click", path=toggle_btn)
        time.sleep(2.5)
    ap.call("call_method", name="SelectRacun", args={"racunId": target_rid})
    ap.call("click", path="frmKasaMobile.btnPreviewR")
    time.sleep(2.0)
    ap.call("call_method", name="StornoExecuteCmd", args={"createNew": create_new, "reasonId": 1})
    time.sleep(3.5)
    return {"scenario": scenario_id, "source_racun_id": target_rid, "create_new": create_new, "status": "DONE"}


def tc09_storno_z_novim(ap: AutopilotClient, ds: dict, ctx: dict) -> dict:
    rid = ctx.get("TC01_racun_id")
    if not rid:
        raise RuntimeError("TC09 requires TC01_racun_id")
    return _run_storno(ap, "TC09_STORNO_Z_NOVIM_RACUNOM", rid, create_new=True, win32=bool(ctx.get("win32")))


def tc10_storno_brez_novega(ap: AutopilotClient, ds: dict, ctx: dict) -> dict:
    rid = ctx.get("TC02_racun_id")
    if not rid:
        raise RuntimeError("TC10 requires TC02_racun_id")
    return _run_storno(ap, "TC10_STORNO_BREZ_NOVEGA_RACUNA", rid, create_new=False, win32=bool(ctx.get("win32")))


SCENARIOS = {
    "TC01": tc01_simple_gotovina,
    "TC02": tc02_paket_kred_rocno,
    "TC03": tc03_lestvica_gost_hotela,
    "TC04": tc04_dobavnica_ros,
    "TC05": tc05_dobavnica_prva,
    "TC06": tc06_popust_99,
    "TC07": tc07_popust_1_pozicija,
    "TC08": tc08_zdruzi_in_split,
    "TC09": tc09_storno_z_novim,
    "TC10": tc10_storno_brez_novega,
}


if __name__ == "__main__":
    ds = load_dataset()
    args = sys.argv[1:]
    win32_mode = "--win32" in args
    args = [a for a in args if a != "--win32"]
    requested = [a.upper() for a in args] if args else list(SCENARIOS.keys())
    if win32_mode:
        print(f"Connecting to RosKasaMobile2027 (Win32 NamedPipe); running: {requested}")
        ap_ctx = AutopilotClient(port=None)
    else:
        pid = ensure_adb_forward()
        print(f"Connected to RosKasaLight on Android (PID={pid}); running: {requested}")
        ap_ctx = AutopilotClient(port=18011)
    ctx: dict = {"win32": win32_mode}
    summary = []
    with ap_ctx as ap:
        if not win32_mode:
            ap.call("set_keep_awake", enabled=True)
        login_pin(ap, ds["environment"]["pin"])
        for key in requested:
            fn = SCENARIOS[key]
            print(f"\n>>> Running {key} ...")
            try:
                res = fn(ap, ds, ctx)
                print(f"<<< {key} OK: {json.dumps(res, ensure_ascii=False)}")
                summary.append(res)
            except Exception as ex:
                err = {"scenario": key, "status": "ERROR", "error": str(ex)}
                print(f"<<< {key} ERROR: {ex}")
                summary.append(err)
    RESULTS_DIR.mkdir(exist_ok=True)
    prefix = "roskasamobile2027" if win32_mode else "roskasalight"
    out = RESULTS_DIR / f"{prefix}_suite_{time.strftime('%Y%m%d_%H%M%S')}.json"
    out.write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"\nSuite report saved: {out}")
