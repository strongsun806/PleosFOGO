"""Verify the FOGO Atlas demo on an emulator with a fresh local profile."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import time
from xml.etree import ElementTree


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--reset", action="store_true", help="Clear FOGO data on this emulator")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-") or not args.reset:
        parser.error("에뮬레이터에서 FOGO 테스트 데이터를 초기화하려면 --reset을 지정하세요.")

    sdk = Path(os.environ.get("ANDROID_HOME") or Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk")
    adb = sdk / "platform-tools/adb.exe"
    package = "com.ssafy.fogo"

    def call(*command):
        return subprocess.check_output([str(adb), "-s", args.serial, *command], encoding="utf-8")

    def shell(*command):
        return call("shell", *command)

    def nodes():
        path = "/data/local/tmp/fogo-test-window.xml"
        output = shell("uiautomator", "dump", path)
        if "dumped" not in output:
            return []
        return list(ElementTree.fromstring(shell("cat", path)).iter("node"))

    def locate(entries, needle, attribute):
        matching = [node for node in entries if node.get(attribute, "") == needle]
        matching += [node for node in entries if node.get(attribute, "").strip().endswith("\n" + needle)]
        return next((node for node in matching if node.get("clickable") == "true" and node.get("enabled") != "false"),
                    next(iter(matching), None))

    def wait_for(needle, timeout=15, attribute="text"):
        end = time.monotonic() + timeout
        last = []
        while time.monotonic() < end:
            last = nodes()
            hit = locate(last, needle, attribute)
            if hit is not None:
                return hit
        visible = [node.get("text") for node in last if node.get("text")]
        raise AssertionError(f"{needle!r} 화면에 없음. 표시된 텍스트: {visible}")

    def tap(needle, attribute="text"):
        node = wait_for(needle, attribute=attribute)
        assert node.get("enabled") != "false", needle
        left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds", "")))
        shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))

    def assert_text_contains(needle):
        assert any(needle in node.get("text", "") for node in nodes()), needle

    def launch():
        shell("am", "start", "--user", user, "-W", "-n", package + "/.MainActivity")

    def replay(city, spot, opening="도시 예시 재생"):
        tap(opening)
        tap(f"{city} · {spot}")
        wait_for("목적지 미리보기")
        tap("주행 재생")
        wait_for("기록 중", 9)
        assert not any(node.get("text", "").strip().endswith("차고") for node in nodes())
        wait_for(f"{spot} 도착")

    user = shell("am", "get-current-user").strip()
    assert shell("pm", "clear", "--user", user, package).strip() == "Success"
    launch()
    tap("시작하기")
    tap("기록 없이 둘러보기")
    tap("이 친구로 시작")
    wait_for("지역별 진행")
    print("온보딩과 위치 기록 없이 둘러보기 확인", flush=True)

    cases = [
        ("강릉시", "경포대", "강릉", "강원", "청록"),
        ("서울특별시", "경복궁", "종로구", "서울", "보라"),
        ("부산광역시", "해운대해수욕장", "해운대구", "부산", "파랑"),
    ]
    for index, (city, spot, region, material, color) in enumerate(cases):
        replay(city, spot, "도시 예시 재생" if index == 0 else "다른 도시 예시")
        wait_for(f"{region} 스티커 +1")
        wait_for(f"{material} 재료 +1")
        wait_for(f"조명 연출 · {color}")
        tap("지도판에 붙이기")
        tap("열린 칸", "content-desc")
        tap("여기 붙이기")
        wait_for(f"{region} 스티커 · 배치 완료", attribute="content-desc")
        tap("지도")
        print(f"{city} 주행 제한, 도착, 보상과 스티커 배치 확인", flush=True)

    replay("강릉시", "정동진역", "다른 도시 예시")
    replay("평창군", "대관령 양떼목장", "다른 도시 예시")
    wait_for("강원 자석 +1")
    tap("지도판에 붙이기")
    tap("자석 1")
    tap("열린 칸", "content-desc")
    tap("여기 붙이기")
    wait_for("강원 자석 · 배치 완료", attribute="content-desc")
    print("강원 세 번째 명소와 자석 배치 확인", flush=True)

    tap("차고")
    tap("공방 가기")
    tap("구름 목도리")
    tap("만들기")
    tap("입히기에서 보기")
    tap("입히기")
    wait_for("구름 목도리 · 장착 중")
    tap("뒤로", "content-desc")
    tap("기록 이어받기(시연)")
    wait_for("그랜저로 이어받기")
    tap("이어받기")
    wait_for("아반떼  →  그랜저")
    tap("차고 보기")
    wait_for("차고에 보관 중 · 아반떼")
    tap("리캡")
    month = next(node for node in nodes() if re.fullmatch(r"\d+월 티켓북 열기", node.get("text", "")))
    tap(month.get("text"))
    tap("다음 장 →")
    wait_for("지도판이 이만큼 넓어졌어요")
    print("공방 제작·장착, 이전 탑승권, 티켓북 2쪽 확인", flush=True)

    shell("am", "force-stop", "--user", user, package)
    launch()
    tap("차고")
    wait_for("그랜저")
    wait_for("구름 목도리")
    wait_for("지도판 배치 4개")
    tap("아틀라스")
    tap("도시 예시 재생")
    tap("부산광역시 · 해운대해수욕장")
    wait_for("첫 도착 보상 수령 완료")
    tap("주행 재생")
    wait_for("기록 중", 9)
    wait_for("첫 도착 보상 수령 완료")
    print("재시작 후 차종·장착·지도판 보존 및 재방문 보상 차단 확인", flush=True)


if __name__ == "__main__":
    main()
