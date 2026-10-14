from __future__ import annotations

import argparse
import hashlib
import json
from math import cos, pi, sin
from pathlib import Path
from xml.sax.saxutils import escape

from reportlab.lib.colors import HexColor
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.utils import ImageReader
from reportlab.pdfgen import canvas
from reportlab.platypus import Paragraph

HERE = Path(__file__).resolve().parent
PAGE_W, PAGE_H = A4
MARGIN = 42
WIDTH = PAGE_W - MARGIN * 2
INK = "#222620"
PAPER = "#F7F4EB"
WHITE = "#FFFEF8"
MUTED = "#62675D"
LINE = "#DCDDD1"
LIME = "#D8F36A"
CORAL = "#F5987C"
GREEN = "#35634A"
WASH = "#EAF0DE"
FONT = "Helvetica"
BOLD = "Helvetica-Bold"


def read_edition(path: Path, draft: bool) -> dict:
    edition = json.loads(path.read_text(encoding="utf-8"))
    if not draft and not edition.get("final"):
        raise ValueError(
            "Final device evidence is missing. Use --draft for layout work."
        )
    for key in ("welcome", "room", "audio"):
        value = edition.get("screenshots", {}).get(key)
        if not value:
            if not draft:
                raise ValueError(f"Missing real screenshot: {key}")
            continue
        source = (HERE / value["file"]).resolve()
        if HERE / "assets" not in source.parents or not source.is_file():
            raise ValueError(
                f"Screenshot must exist under docs/walkthrough/assets: {key}"
            )
        if value.get("contains_invite") is not False:
            raise ValueError(f"Screenshot needs a no-invite review: {key}")
        if not value.get("description"):
            raise ValueError(f"Screenshot needs a source description: {key}")
        if value.get("sha256") != hashlib.sha256(source.read_bytes()).hexdigest():
            raise ValueError(f"Screenshot hash does not match: {key}")
    if len(edition.get("checks", [])) != 3:
        raise ValueError("The evidence page needs three concise result groups")
    if not draft:
        if not edition.get("evidence"):
            raise ValueError("Final edition needs evidence paths")
        for check in edition["checks"]:
            if check.get("status") not in ("passed", "failed", "partial", "not_run"):
                raise ValueError("Final edition still contains pending results")
    return edition


class Guide:
    def __init__(self, output: Path, edition: dict, draft: bool):
        self.edition = edition
        self.draft = draft
        self.page_number = 0
        self.c = canvas.Canvas(str(output), pagesize=A4, invariant=1)
        self.c.setTitle("PocketDisco | Your room, your people")
        self.c.setAuthor("PocketDisco")
        self.c.setSubject("Illustrated internal Android test walkthrough")
        self.c.setCreator("PocketDisco walkthrough generator")

    def rect(self, x, top, width, height, fill, radius=0, stroke=None):
        self.c.setFillColor(HexColor(fill))
        self.c.setStrokeColor(HexColor(stroke or fill))
        self.c.setLineWidth(0.75)
        self.c.roundRect(
            x,
            PAGE_H - top - height,
            width,
            height,
            radius,
            fill=1,
            stroke=bool(stroke),
        )

    def line(self, x1, y1, x2, y2, color=LINE, width=1):
        self.c.setStrokeColor(HexColor(color))
        self.c.setLineWidth(width)
        self.c.line(x1, PAGE_H - y1, x2, PAGE_H - y2)

    def circle(self, x, top, radius, fill, stroke=None, width=1):
        self.c.setFillColor(HexColor(fill))
        self.c.setStrokeColor(HexColor(stroke or fill))
        self.c.setLineWidth(width)
        self.c.circle(x, PAGE_H - top, radius, fill=1, stroke=bool(stroke))

    def text(self, text, x, top, size=11, color=INK, font=FONT):
        self.c.setFillColor(HexColor(color))
        self.c.setFont(font, size)
        self.c.drawString(x, PAGE_H - top - size, text)

    def para(
        self,
        text,
        x,
        top,
        width,
        size=11,
        color=MUTED,
        leading=None,
        font=FONT,
        max_height=None,
    ):
        style = ParagraphStyle(
            "body",
            fontName=font,
            fontSize=size,
            textColor=HexColor(color),
            leading=leading or size * 1.4,
            spaceAfter=0,
        )
        paragraph = Paragraph(text, style)
        _, height = paragraph.wrap(width, PAGE_H)
        if max_height is not None and height > max_height + 0.5:
            raise ValueError(f"Text overflows page {self.page_number}: {text[:60]}")
        if top + height > PAGE_H - 42:
            raise ValueError(
                f"Text crosses footer on page {self.page_number}: {text[:60]}"
            )
        paragraph.drawOn(self.c, x, PAGE_H - top - height)
        return height

    def pill(self, text, x, top, fill=LIME, color=INK, size=8):
        width = self.c.stringWidth(text, BOLD, size) + 20
        self.rect(x, top, width, 23, fill, 11)
        self.text(text, x + 10, top + 6, size, color, BOLD)
        return width

    def start(self, title, dark=False):
        if self.page_number:
            self.c.showPage()
        self.page_number += 1
        self.rect(0, 0, PAGE_W, PAGE_H, INK if dark else PAPER)
        color = PAPER if dark else INK
        self.circle(MARGIN + 6, 36, 6, LIME if dark else INK)
        self.circle(MARGIN + 6, 36, 1.5, INK if dark else LIME)
        self.text("pocketdisco", MARGIN + 20, 28, 12, color, BOLD)
        self.text("FIELD GUIDE / ANDROID", 398, 30, 8, color)
        key = f"page-{self.page_number}"
        self.c.bookmarkPage(key)
        self.c.addOutlineEntry(title, key, level=0)
        self.line(
            MARGIN,
            PAGE_H - 37,
            PAGE_W - MARGIN,
            PAGE_H - 37,
            "#42483C" if dark else LINE,
        )
        label = "DRAFT / " if self.draft else ""
        self.text(
            f"{label}INTERNAL TEST BUILD  /  {self.edition['date']}",
            MARGIN,
            PAGE_H - 27,
            7.5,
            color,
        )
        self.text(
            f"{self.page_number:02}", PAGE_W - MARGIN - 12, PAGE_H - 29, 10, color, BOLD
        )

    def heading(self, kicker, title, intro=None):
        self.text(kicker.upper(), MARGIN, 83, 9, GREEN, BOLD)
        height = self.para(title, MARGIN, 108, WIDTH, 34, INK, 38, BOLD)
        if intro:
            self.para(intro, MARGIN, 121 + height, WIDTH - 16, 11, max_height=62)

    def number(self, number, x, top, fill=LIME):
        self.circle(x + 12, top + 12, 12, fill)
        self.text(str(number), x + 8.5, top + 6, 10, INK, BOLD)

    def step(self, number, title, body, x, top, width=260, height=82):
        self.number(number, x, top)
        self.text(title, x + 34, top + 3, 12, INK, BOLD)
        self.para(body, x + 34, top + 24, width - 34, 10.5, max_height=height - 24)

    def note(self, title, body, top, fill=WASH, x=MARGIN, width=WIDTH, height=86):
        self.rect(x, top, width, height, fill, 15)
        self.text(title, x + 18, top + 16, 11, INK, BOLD)
        self.para(body, x + 18, top + 36, width - 36, 10, max_height=height - 46)

    def vinyl(self, x, top, radius, fill=INK, label=LIME):
        self.circle(x, top, radius, fill)
        for scale in (0.87, 0.72, 0.57):
            self.circle(x, top, radius * scale, fill, "#4C5544", 0.8)
        self.circle(x, top, radius * 0.31, label)
        self.circle(x, top, max(2, radius * 0.06), PAPER)

    def sparkle(self, x, top, size, color=CORAL):
        for angle in (0, pi / 4, pi / 2, 3 * pi / 4):
            dx, dy = cos(angle) * size, sin(angle) * size
            self.line(x - dx, top - dy, x + dx, top + dy, color, 2)

    def small_phone(self, x, top, width=64, fill=WHITE, accent=LIME, symbol="disc"):
        height = width * 1.72
        self.rect(x, top, width, height, INK, width * 0.16)
        self.rect(x + 4, top + 4, width - 8, height - 8, fill, width * 0.12)
        self.rect(x + width * 0.35, top + 9, width * 0.3, 3, INK, 1.5)
        if symbol == "disc":
            self.vinyl(x + width / 2, top + height * 0.46, width * 0.28, GREEN, accent)
            self.rect(x + 12, top + height - 26, width - 24, 5, accent, 2)
        else:
            for index, line_width in enumerate((0.56, 0.4, 0.52)):
                self.rect(
                    x + 10,
                    top + 29 + index * 18,
                    width * line_width,
                    12,
                    accent if index % 2 else WASH,
                    5,
                )
        return height

    def screenshot(self, key, x, top, width=176):
        item = self.edition.get("screenshots", {}).get(key)
        if item:
            image = ImageReader(str(HERE / item["file"]))
            image_w, image_h = image.getSize()
            height = width * image_h / image_w
            if height > 442:
                raise ValueError(f"Screenshot is too tall for its page: {key}")
            self.rect(x - 7, top - 7, width + 14, height + 14, INK, 18)
            self.c.drawImage(
                image, x, PAGE_H - top - height, width=width, height=height, mask="auto"
            )
            self.para(
                "REAL APP SCREEN / INTERNAL BUILD",
                x - 7,
                top + height + 22,
                width + 16,
                7,
                MUTED,
                10,
            )
            return height
        self.rect(x - 7, top - 7, width + 14, 402, INK, 18)
        self.rect(x, top, width, 388, WASH, 12)
        self.para(
            "REAL SCREENSHOT<br/>PENDING",
            x + 18,
            top + 160,
            width - 36,
            14,
            GREEN,
            20,
            BOLD,
        )
        self.para(
            "Draft layout only.<br/>Not a mock app screen.",
            x + 18,
            top + 218,
            width - 36,
            10,
            MUTED,
        )
        return 388

    def arrow(self, x1, top, x2, color=GREEN):
        self.line(x1, top, x2, top, color, 1.4)
        self.line(x2 - 5, top - 3, x2, top, color, 1.4)
        self.line(x2 - 5, top + 3, x2, top, color, 1.4)

    def cover(self):
        self.start("Start here", dark=True)
        self.pill("YOUR FIRST POCKETDISCO SESSION", MARGIN, 92)
        self.para(
            "Your room.<br/>Your people.<br/>A little disco.",
            MARGIN,
            142,
            WIDTH,
            53,
            PAPER,
            55,
            BOLD,
        )
        self.para(
            "A hands-on tour of private rooms, live chat<br/>and the audio tests you can try today.",
            MARGIN + 2,
            329,
            WIDTH,
            13,
            PAPER,
            19,
        )
        self.circle(460, 554, 128, LIME)
        self.vinyl(447, 550, 109)
        self.c.saveState()
        self.c.translate(160, PAGE_H - 527)
        self.c.rotate(11)
        self.c.translate(-160, -(PAGE_H - 527))
        self.small_phone(103, 443, 116, PAPER, CORAL, "chat")
        self.c.restoreState()
        self.c.saveState()
        self.c.translate(290, PAGE_H - 594)
        self.c.rotate(-13)
        self.c.translate(-290, -(PAGE_H - 594))
        self.small_phone(232, 493, 116, WHITE, LIME)
        self.c.restoreState()
        self.sparkle(69, 454, 15, CORAL)
        self.sparkle(372, 414, 12, LIME)
        self.text(
            "A small guide for a proper test drive.", MARGIN, 712, 13, PAPER, BOLD
        )
        self.para(
            "Internal Android alpha. Shared Internet playback and Play Store release are not ready yet.",
            MARGIN,
            738,
            WIDTH - 8,
            10,
            "#BBC3AF",
            14,
        )

    def overview(self):
        self.start("What you can do")
        self.heading(
            "01 / What you can try",
            "Start with the room.<br/>Know what plays.",
            "Three experiences live here. They do not all share the same audio timeline.",
        )
        cards = [
            (
                "01",
                "Private rooms",
                "Create, invite, join, see who is here, set your readiness and exchange text chat.",
                "LIVE ROOM CONTROLS",
                LIME,
            ),
            (
                "02",
                "Local sound check",
                "Play the bundled 24-second generated clip. Pause, seek and replay on this phone only.",
                "NOT SHARED PLAYBACK",
                WASH,
            ),
            (
                "03",
                "Nearby audio lab",
                "An internal capture and relay experiment with separate admission, Android consent and paired phones.",
                "EXPERIMENTAL",
                CORAL,
            ),
        ]
        for index, (number, title, body, badge, fill) in enumerate(cards):
            top = 248 + index * 130
            self.rect(MARGIN, top, WIDTH, 114, WHITE, 16, LINE)
            self.circle(MARGIN + 39, top + 41, 24, fill)
            self.text(number, MARGIN + 25, top + 29, 23, INK, BOLD)
            self.text(title, MARGIN + 77, top + 17, 18, INK, BOLD)
            self.para(body, MARGIN + 77, top + 44, WIDTH - 99, 10.4, max_height=43)
            self.text(badge, MARGIN + 77, top + 92, 7.5, GREEN, BOLD)
        self.note(
            "Not in this build",
            "No shared Internet music timeline, Spotify integration, automatic Bluetooth mesh, Windows release or public Play release.",
            650,
            height=82,
        )
        self.text(
            "SETUP 03   /   ROOM 04   /   AUDIO 05   /   LAB 06-07   /   TEST LAP 09",
            MARGIN,
            755,
            8,
            MUTED,
            BOLD,
        )

    def setup(self):
        self.start("Get connected")
        self.heading("02 / Before you tap", "Get connected.<br/>Then get together.")
        self.screenshot("welcome", MARGIN + 7, 224, 168)
        self.step(
            1,
            "Install the internal APK",
            "Use the handed-off build for this device. If Android reports a signing conflict, stop and ask for the matching APK. Do not uninstall your existing app.",
            257,
            224,
            296,
            116,
        )
        self.step(
            2,
            "Keep the private route alive",
            "Leave USB connected and debugging authorized. The PC needs the running SSH tunnel and this phone's ADB port mapping. USB tethering alone is not enough.",
            257,
            352,
            296,
            116,
        )
        self.step(
            3,
            "Open PocketDisco",
            "The internal server address is <b>http://127.0.0.1:8000</b>. It is saved under <b>Development server</b>. Saving the address does not test the connection.",
            257,
            480,
            296,
            116,
        )
        top = 656
        self.rect(MARGIN, top, WIDTH, 57, WASH, 14)
        self.text("PHONE", 62, top + 19, 9, GREEN, BOLD)
        self.arrow(105, top + 27, 170)
        self.text("USB / ADB", 112, top + 11, 6.8, MUTED)
        self.text("THIS PC", 185, top + 19, 9, GREEN, BOLD)
        self.arrow(246, top + 27, 333)
        self.text("SSH TUNNEL", 259, top + 11, 6.8, MUTED)
        self.text("PRIVATE BACKEND", 349, top + 19, 9, GREEN, BOLD)
        self.para(
            "<b>Outside this setup?</b> Public HTTPS is still pending. Ports 50080 / 50443 are the available router ports; a working domain and trusted HTTPS are not configured yet. Unplugged phones cannot use this loopback route.",
            MARGIN,
            732,
            WIDTH,
            9.2,
            MUTED,
            13,
            max_height=52,
        )

    def room(self):
        self.start("Create, invite and chat")
        self.heading(
            "03 / Bring your people",
            "A room in five minutes.",
            "Use two configured clients for the full loop. The second can be another phone or the test emulator.",
        )
        self.screenshot("room", 374, 238, 170)
        self.step(
            1,
            "Make your room",
            "Choose <b>Create a room</b>, enter your name and a room name, then tap <b>Create my room</b>.",
            MARGIN,
            239,
            302,
            80,
        )
        self.step(
            2,
            "Bring someone in",
            "Use <b>Share room invite</b>. On the second client, choose <b>Join friends</b>, enter a name and the 12-character code, then join.",
            MARGIN,
            332,
            302,
            100,
        )
        self.step(
            3,
            "Check the room",
            "Use the <b>Room</b> shortcut. Both names should appear under <b>Your people</b>. Tap <b>I'm ready</b> on each client and watch the status update.",
            MARGIN,
            443,
            302,
            90,
        )
        self.step(
            4,
            "Say hello both ways",
            "Use <b>Chat</b> and send a short message from each client. It should appear once on both. The draft clears only after the server accepts the send.",
            MARGIN,
            547,
            302,
            90,
        )
        self.note(
            "Ready means ready, not play",
            "The ready toggle tells people you are settled in. It never starts the demo or starts shared music. Keep the invite within your test group.",
            692,
            height=80,
        )

    def audio(self):
        self.start("Try the local sound check")
        self.heading("04 / Press play", "A little sound check.<br/>This phone only.")
        self.screenshot("audio", MARGIN + 7, 228, 168)
        self.pill("24 SECONDS / GENERATED AUDIO", 256, 226, WASH, GREEN, 7.5)
        self.step(
            1,
            "Start quietly",
            "Turn media volume down. Tap the <b>Audio</b> shortcut, then <b>Play demo</b> in Audio corner. Its playhead should move and this phone should play.",
            257,
            275,
            296,
            100,
        )
        self.step(
            2,
            "Pause, seek, replay",
            "Tap <b>Pause demo</b>. Try <b>Back 5 seconds</b> and <b>Forward 5 seconds</b>. Seeking pauses. Tap <b>Play demo</b> to resume, or <b>Replay demo</b> after the clip ends.",
            257,
            389,
            296,
            116,
        )
        self.step(
            3,
            "Put it in the background",
            "Go Home, then return. It should remain paused, not resume by itself. Leaving the room also releases the player.",
            257,
            519,
            296,
            98,
        )
        self.note(
            "The useful comparison",
            "Playing on phone A should not start phone B. A moving playhead proves local playback activity, not cross-phone synchronization or measured speaker timing.",
            674,
            height=87,
        )

    def nearby(self):
        self.start("Understand the nearby lab")
        self.heading(
            "05 / Optional experiment",
            "Nearby is a separate<br/>audio session.",
            "The nearby code controls admission. Your Internet room code does not.",
        )
        top = 253
        self.rect(MARGIN, top, WIDTH, 197, WASH, 18)
        self.small_phone(83, top + 27, 62, WHITE, CORAL)
        self.small_phone(266, top + 27, 62)
        self.small_phone(448, top + 27, 62)
        self.arrow(157, top + 84, 250)
        self.arrow(339, top + 84, 431)
        self.text("PAIRED LINK", 171, top + 65, 7, GREEN, BOLD)
        self.text("TEST RELAY", 354, top + 65, 7, GREEN, BOLD)
        self.text("SOURCE", 89, top + 147, 8, INK, BOLD)
        self.text("RECEIVER 1", 270, top + 147, 8, INK, BOLD)
        self.text("RECEIVER 2", 452, top + 147, 8, INK, BOLD)
        self.text("Original speaker is not delayed", 64, top + 173, 8, MUTED)
        self.text("Illustration, not a measured result", 354, top + 173, 7, MUTED)
        self.para(
            "<b>This is a low-fidelity diagnostic.</b> It transmits 16 kHz mono PCM with manual pairing, up to three direct links and three hops. It does not automatically link every phone. Android consent and the source app's capture policy still apply. The walkthrough on the next page uses generated audio only.",
            MARGIN,
            475,
            WIDTH,
            11,
            max_height=86,
        )
        self.note(
            "Keep this outside the acceptance lap",
            "The latest recorded Bluetooth regression lost clock quality. Earlier short successes do not make it reliable. Software timestamps are not an acoustic sync measurement. The emulator substitutes USB/TCP, not a real Bluetooth radio.",
            576,
            fill="#F7DFD2",
            height=105,
        )
        self.para(
            "<b>Exit safely:</b> Back returns to the room and ends nearby audio; room chat reconnects. Stop the original music app separately. This lab does not turn a phone into an ordinary Bluetooth speaker or grant rights to YouTube Music / Spotify catalog audio. A patched APK does not change that.",
            MARGIN,
            705,
            WIDTH,
            10,
            max_height=70,
        )

    def nearby_recipe(self):
        self.start("Try one paired-phone link")
        self.heading(
            "06 / Optional paired-phone lab",
            "Try one nearby link.",
            "Use two physical Android 10+ phones within Bluetooth range. Start quietly and keep the receiving screen open.",
        )
        self.note(
            "A sends. B receives.",
            "Install matching current Capture Lab builds on both and Test Source on A. Pair A and B in Android settings. Open Capture Lab, or use Open nearby audio from a connected internal room.",
            211,
            height=80,
        )
        self.line(MARGIN + 12, 322, MARGIN + 12, 672, LINE, 1.2)
        steps = (
            (
                "Share one fresh code",
                "Stop any old sessions. On A, tap <b>New session code</b>. Put the same 32 hexadecimal characters in B's <b>Session code</b> field. This is not the room's 12-character invite.",
            ),
            (
                "Make the paired link",
                "A taps <b>Host paired Bluetooth</b>. B taps <b>Receive paired Bluetooth</b>, then selects A's paired name. Allow Bluetooth access if requested. Wait for one peer on each.",
            ),
            (
                "Choose the generated source",
                "On A only, select <b>Generated source</b> and tap <b>Start capture</b>. Read and approve Android's capture prompt if you intend to transmit. Do not start capture on B.",
            ),
            (
                "Play the test tone",
                "On A, tap <b>Open generated source</b>, then <b>Play allowed</b>. On B, use <b>Refresh metrics</b> and look for Clock ready, Output: playing and increasing received frames.",
            ),
            (
                "Check the limits, too",
                "The source speaker plays first; receivers use a 1.5-second buffer. After startup settles, a clock that stays waiting, repeated realignments or silence is not a pass. A and B are not synchronized speakers.",
            ),
            (
                "Stop both sides",
                "Tap <b>Stop</b> in Test Source. Return to the lab and tap <b>Stop session</b> on both phones. Back exits the lab; returning to the room ends nearby audio and reconnects chat.",
            ),
        )
        for index, (title, body) in enumerate(steps):
            self.step(index + 1, title, body, MARGIN, 310 + index * 70, WIDTH, 68)
        self.para(
            "<b>Using the emulator?</b> Ask the tester to configure the USB/TCP route and use the USB/TCP test buttons instead. That is not a Bluetooth test. Full setup: <b>experiments/android-capture-relay/README.md</b>.",
            MARGIN,
            749,
            WIDTH,
            9,
            max_height=40,
        )

    def recovery(self):
        self.start("Test the rough edges")
        self.heading(
            "07 / The useful rough edges",
            "Break the connection.<br/>Keep your place.",
            "Try these with a disposable test room. Keep personal messages and real invite codes out of screenshots.",
        )
        states = [("CONNECTED", LIME), ("OFFLINE", CORAL), ("RECOVERED", WASH)]
        for index, (label, fill) in enumerate(states):
            x = MARGIN + index * 181
            self.rect(x, 251, 150, 42, fill, 13)
            self.text(label, x + 20, 266, 9, INK, BOLD)
            if index < 2:
                self.arrow(x + 156, 272, x + 174)
        cards = [
            (
                "01 / Background",
                "Go Home on one client. Send a message from the other, then return.",
                "The room reconnects and shows the latest snapshot. Local demo audio stays paused.",
            ),
            (
                "02 / Lost route",
                "With a tester coordinating the PC, briefly remove only this test phone's API route, then restore it.",
                "Offline controls stay unavailable. Use Try again or Return to your room after recovery.",
            ),
            (
                "03 / Restart",
                "Close PocketDisco, then reopen it. For a real process-death test, ask the tester to stop only this app.",
                "The saved guest and room recover. Audio must not start without another tap.",
            ),
            (
                "04 / Leave",
                "Tap <b>Leave</b>, then <b>Leave room</b> in the confirmation. Repeat on the other client.",
                "Welcome appears without an error. If leaving fails, use Room / View error to see why. The last member leaving closes the room.",
            ),
        ]
        for index, (title, action, outcome) in enumerate(cards):
            x = MARGIN + (index % 2) * 263
            y = 322 + (index // 2) * 194
            self.rect(x, y, 248, 177, WHITE, 15, LINE)
            self.text(title, x + 17, y + 16, 13, INK, BOLD)
            self.para(action, x + 17, y + 43, 214, 10.2, max_height=59)
            self.para(
                f"<b>Look for:</b> {outcome}",
                x + 17,
                y + 108,
                214,
                9.7,
                GREEN,
                max_height=57,
            )
        self.para(
            "<b>Do not reset to recover.</b> Uninstalling or clearing storage removes the saved guest session. A connection error is not a reason to erase app data.",
            MARGIN,
            732,
            WIDTH,
            10.5,
            max_height=44,
        )

    def checklist(self):
        self.start("Your acceptance lap")
        self.heading(
            "08 / Make it yours",
            "Your ten-minute<br/>acceptance lap.",
            "Tick a box only after seeing the result. Use a PDF reader or print this page. Leave blocked steps blank.",
        )
        self.text("CHECK", MARGIN + 10, 245, 8, MUTED, BOLD)
        self.text("TRY THIS", MARGIN + 61, 245, 8, MUTED, BOLD)
        self.text("A PASS LOOKS LIKE", MARGIN + 248, 245, 8, MUTED, BOLD)
        rows = [
            ("Open the app", "Welcome appears without a crash."),
            ("Create and join", "Two names appear in the same room."),
            ("Toggle readiness", "The other client sees the new status."),
            ("Chat both ways", "Each message arrives once on both."),
            ("Return from Home", "New messages and presence recover."),
            ("Lose and restore route", "Retry recovers; controls follow connection."),
            ("Play the local demo", "Only the phone you tapped plays."),
            ("Pause, seek, background", "Seek and return never auto-resume."),
            ("Leave on both clients", "Both reach Welcome without an error."),
        ]
        for index, (action, expected) in enumerate(rows):
            top = 270 + index * 43
            if index % 2 == 0:
                self.rect(MARGIN, top, WIDTH, 42, WHITE, 7)
            self.c.acroForm.checkbox(
                name=f"check-{index + 1}",
                tooltip=action,
                x=MARGIN + 16,
                y=PAGE_H - top - 27,
                size=13,
                buttonStyle="check",
                checked=False,
                borderWidth=0.8,
                borderColor=HexColor(MUTED),
                fillColor=HexColor(PAPER),
                textColor=HexColor(GREEN),
                forceBorder=True,
            )
            self.text(action, MARGIN + 61, top + 14, 10.2, INK, BOLD)
            self.para(expected, MARGIN + 248, top + 12, WIDTH - 260, 9.5, max_height=30)
        self.text("ONE THING I LOVED", MARGIN, 686, 8, GREEN, BOLD)
        self.text("ONE THING TO FIX", MARGIN, 739, 8, GREEN, BOLD)
        for name, top in (("One thing I loved", 704), ("One thing to fix", 755)):
            self.c.acroForm.textfield(
                name=name,
                tooltip=name,
                x=MARGIN,
                y=PAGE_H - top - 24,
                width=WIDTH,
                height=24,
                fontName=FONT,
                fontSize=10,
                borderStyle="underlined",
                borderWidth=0.7,
                borderColor=HexColor(LINE),
                fillColor=HexColor(PAPER),
                textColor=HexColor(INK),
                forceBorder=True,
            )

    def evidence(self):
        self.start("Results and next steps")
        self.heading("09 / The test report", "What we tested.<br/>What comes next.")
        self.para(
            escape(self.edition["device_note"]), MARGIN, 212, WIDTH, 11, max_height=49
        )
        self.pill("TESTED BUILD: " + self.edition["build"], MARGIN, 272, INK, PAPER, 8)
        for index, check in enumerate(self.edition["checks"]):
            top = 317 + index * 68
            self.rect(MARGIN, top, WIDTH, 61, WHITE, 13, LINE)
            self.text(check["label"], MARGIN + 17, top + 12, 11, INK, BOLD)
            self.para(
                escape(check["result"]),
                MARGIN + 17,
                top + 31,
                WIDTH - 34,
                9.4,
                max_height=27,
            )
        self.text("STILL NEEDED BEFORE A PUBLIC RELEASE", MARGIN, 547, 9, GREEN, BOLD)
        self.para(
            "Trusted public HTTPS and a deployable endpoint. A connected shared-room audio timeline. Reliable Bluetooth timing and two-phone acoustic measurements. Cleared media rights. Privacy, deletion, moderation, signing and Android compatibility checks.",
            MARGIN,
            573,
            WIDTH,
            11,
            max_height=65,
        )
        self.note(
            "Tell us what actually happened",
            "Include the screen, action, expected result and observed result. Add the build above, device model and whether USB was connected. Do not include invite codes, nearby codes, tokens, passwords or private chat.",
            660,
            height=96,
        )
        self.text(
            "Detailed evidence and reproduction notes: docs/walkthrough/README.md",
            MARGIN,
            771,
            8,
            MUTED,
        )

    def build(self):
        self.cover()
        self.overview()
        self.setup()
        self.room()
        self.audio()
        self.nearby()
        self.nearby_recipe()
        self.recovery()
        self.checklist()
        self.evidence()
        self.c.save()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--edition", type=Path, default=HERE / "edition.json")
    parser.add_argument(
        "--output", type=Path, default=HERE / "PocketDisco-Walkthrough.pdf"
    )
    parser.add_argument("--draft", action="store_true")
    args = parser.parse_args()
    edition = read_edition(args.edition.resolve(), args.draft)
    Guide(args.output.resolve(), edition, args.draft).build()
    print(f"Created {args.output.name}: 10 pages")


if __name__ == "__main__":
    main()
