#!/usr/bin/env python3
"""Generate the Private Data Union demo corpus (PC side, Python 3.12, needs: pip install pillow reportlab).

Everything is synthetic: invented shops, invented people, invented serial and policy numbers, and every
file carries a small "SYNTHETIC DEMO DATA" footer. Nothing is copied from a real document.

Output (mirrors the phone paths so a push is a plain directory copy):
    docs/demo-corpus/<A|B|C>/Pictures/SwarmDemo/*.jpg
    docs/demo-corpus/<A|B|C>/Documents/SwarmDemo/*.{pdf,docx,txt,md}
    docs/demo-corpus/B/Documents/SwarmDemo/Private/Salary_Slip_Sep2026.pdf   (the file that must be withheld)

Usage:
    python scripts/make-demo-corpus.py            # generate only
    python scripts/make-demo-corpus.py --push     # generate, then adb push to the three phones and print every adb command

Story: one owner (Rhea Testerman) bought a laptop. The photographed bill is on Phone B, the extended-warranty
certificate (text PDF) is on Phone A, Phone C holds unrelated files. "Where is the warranty bill for my laptop?"
asked on any phone must cite B's bill and A's certificate. B's salary slip sits in a folder named Private and
may only show up as a withheld count.
"""
import argparse
import datetime as dt
import os
import random
import subprocess
import sys
import zipfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont
from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas

ROOT = Path(__file__).resolve().parent.parent / "docs" / "demo-corpus"
ADB = os.environ.get("ADB", r"C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe")
PHONES = {"A": "192.168.242.89:5555", "B": "192.168.242.23:5555", "C": "192.168.242.76:5555"}
FOOTER = "SYNTHETIC DEMO DATA - not a real document"

FONT_DIRS = ["C:/Windows/Fonts", "/usr/share/fonts/truetype/dejavu", "/Library/Fonts"]


def font(names, size):
    for n in names:
        for d in FONT_DIRS:
            p = os.path.join(d, n)
            if os.path.exists(p):
                return ImageFont.truetype(p, size)
    return ImageFont.load_default(size)


MONO = ["cour.ttf", "DejaVuSansMono.ttf"]
MONO_B = ["courbd.ttf", "DejaVuSansMono-Bold.ttf"]
SANS = ["arial.ttf", "DejaVuSans.ttf"]
SANS_B = ["arialbd.ttf", "DejaVuSans-Bold.ttf"]
HAND = ["segoepr.ttf", "LHANDW.TTF", "BRADHITC.TTF", "DejaVuSans.ttf"]
DEVANAGARI = ["Nirmala.ttc", "NotoSansDevanagari-Regular.ttf", "DejaVuSans.ttf"]


# ---- image helpers ----------------------------------------------------------------------------------------

def draw_lines(lines, width, pad=60, bg=(250, 248, 240), ink=(30, 30, 34), gap=1.45):
    """lines: list of (text, font) or (text, font, 'right'/'center'). Returns an RGB page image."""
    probe = ImageDraw.Draw(Image.new("RGB", (10, 10)))
    heights = [probe.textbbox((0, 0), t or "Ag", font=f)[3] for t, f, *_ in lines]
    total = int(sum(h * gap for h in heights)) + 2 * pad
    img = Image.new("RGB", (width, total), bg)
    d = ImageDraw.Draw(img)
    y = pad
    for (t, f, *al), h in zip(lines, heights):
        w = probe.textlength(t, font=f)
        x = pad
        if al and al[0] == "right":
            x = width - pad - w
        elif al and al[0] == "center":
            x = (width - w) / 2
        d.text((x, y), t, font=f, fill=ink)
        y += h * gap
    return img


def photographed(page, seed, angle=1.5, noise=7, blur=0.7, bg=(92, 78, 66), margin=90):
    """Make a flat page look photographed: table background, slight rotation and keystone, uneven light, noise, blur."""
    rnd = random.Random(seed)
    w, h = page.size
    canvas_img = Image.new("RGB", (w + 2 * margin, h + 2 * margin), bg)
    cd = ImageDraw.Draw(canvas_img)
    for _ in range(900):  # table grain
        x, y = rnd.randrange(canvas_img.width), rnd.randrange(canvas_img.height)
        shade = rnd.randint(-14, 14)
        cd.line((x, y, x + rnd.randint(20, 120), y + rnd.randint(-2, 2)), fill=tuple(max(0, min(255, c + shade)) for c in bg))
    shadow = Image.new("L", canvas_img.size, 0)
    ImageDraw.Draw(shadow).rectangle((margin + 14, margin + 18, margin + w + 14, margin + h + 18), fill=120)
    canvas_img.paste((0, 0, 0), mask=shadow.filter(ImageFilter.GaussianBlur(14)))
    canvas_img.paste(page, (margin, margin))
    img = canvas_img.rotate(angle, resample=Image.BICUBIC, expand=True, fillcolor=bg)
    k = 0.00004 * rnd.choice([-1, 1])  # perspective keystone
    W, H = img.size
    img = img.transform(img.size, Image.PERSPECTIVE, (1, 0, 0, 0, 1, 0, k, 0), resample=Image.BICUBIC, fillcolor=bg)
    light = Image.linear_gradient("L").resize(img.size).rotate(rnd.choice([0, 90, 180, 270]))
    img = Image.composite(img, ImageEnhance.Brightness(img).enhance(0.78), light.point(lambda v: 150 + v // 3))
    if noise:
        n = Image.effect_noise(img.size, noise).convert("RGB")
        img = Image.blend(img, n, 0.06)
    if blur:
        img = img.filter(ImageFilter.GaussianBlur(blur))
    long_side = max(img.size)
    if long_side > 2000:
        s = 2000 / long_side
        img = img.resize((int(img.width * s), int(img.height * s)), Image.LANCZOS)
    return img


def save_jpeg(img, path: Path, taken: dt.datetime):
    path.parent.mkdir(parents=True, exist_ok=True)
    exif = Image.Exif()
    stamp = taken.strftime("%Y:%m:%d %H:%M:%S")
    exif[0x0132] = stamp  # DateTime
    exif.get_ifd(0x8769)[0x9003] = stamp  # DateTimeOriginal
    img.save(path, "JPEG", quality=88, exif=exif)
    ts = taken.timestamp()
    os.utime(path, (ts, ts))


def text_page(path: Path, lines, taken, seed, width=1100, **kw):
    save_jpeg(photographed(draw_lines(lines, width), seed, **kw), path, taken)


def no_text_photo(path: Path, taken, seed, hue):
    """A scenery-like picture with no text at all, to show the 'fewer than 20 characters' skip."""
    rnd = random.Random(seed)
    img = Image.linear_gradient("L").resize((1280, 960)).convert("RGB")
    img = Image.merge("RGB", [img.getchannel(0).point(lambda v, a=a: min(255, int(v * a))) for a in hue])
    d = ImageDraw.Draw(img)
    for _ in range(14):
        x, y, r = rnd.randrange(1280), rnd.randrange(480, 960), rnd.randint(40, 180)
        c = tuple(rnd.randint(20, 120) for _ in range(3))
        d.ellipse((x - r, y - r // 2, x + r, y + r // 2), fill=c)
    save_jpeg(img.filter(ImageFilter.GaussianBlur(2)), path, taken)


# ---- document helpers -------------------------------------------------------------------------------------

def text_pdf(path: Path, title, lines):
    path.parent.mkdir(parents=True, exist_ok=True)
    c = canvas.Canvas(str(path), pagesize=A4)
    c.setTitle(title)
    c.setAuthor("Synthetic demo generator")
    w, h = A4
    y = h - 70
    c.setFont("Helvetica-Bold", 17)
    c.drawString(60, y, title)
    y -= 34
    for ln in lines:
        bold = ln.startswith("**")
        ln = ln.lstrip("*")
        c.setFont("Helvetica-Bold" if bold else "Helvetica", 11)
        c.drawString(60, y, ln)
        y -= 18
    c.setFont("Helvetica-Oblique", 8)
    c.drawString(60, 40, FOOTER)
    c.save()


def scanned_pdf(path: Path, lines, seed):
    """An image-only PDF (no text layer): the PdfRenderer + OCR path."""
    path.parent.mkdir(parents=True, exist_ok=True)
    page = photographed(draw_lines(lines, 1100), seed, angle=0.6, noise=4, blur=0.5, bg=(205, 205, 205), margin=40)
    page.convert("RGB").save(path, "PDF", resolution=140)


def docx(path: Path, paragraphs):
    path.parent.mkdir(parents=True, exist_ok=True)

    def esc(s):
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    body = "".join(f"<w:p><w:r><w:t xml:space=\"preserve\">{esc(p)}</w:t></w:r></w:p>" for p in paragraphs + [FOOTER])
    document = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>'
        + body + "</w:body></w:document>"
    )
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml",
                   '<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
                   '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
                   '<Default Extension="xml" ContentType="application/xml"/>'
                   '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
        z.writestr("_rels/.rels",
                   '<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                   '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
        z.writestr("word/document.xml", document)


def plain(path: Path, text, when: dt.datetime):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text.strip() + "\n\n" + FOOTER + "\n", encoding="utf-8")
    ts = when.timestamp()
    os.utime(path, (ts, ts))


def stamp_file(path: Path, when: dt.datetime):
    ts = when.timestamp()
    os.utime(path, (ts, ts))


# ---- the corpus -------------------------------------------------------------------------------------------

def phone_b():
    pics = ROOT / "B" / "Pictures" / "SwarmDemo"
    docs = ROOT / "B" / "Documents" / "SwarmDemo"
    big, bold, small = font(MONO, 34), font(MONO_B, 40), font(MONO, 27)
    # the receipt the demo is about
    text_page(pics / "IMG_2231.jpg", [
        ("ZORBIK ELECTRONICS HUB (DEMO)", bold, "center"),
        ("Shop 12, Imaginary Arcade, Fictional Nagar", small, "center"),
        ("Bengaluru 560000   GSTIN 00DEMO0000Z0Z0", small, "center"),
        ("", small),
        ("TAX INVOICE / WARRANTY BILL - LAPTOP", bold),
        ("Invoice No : ZEH/25-26/004417", big),
        ("Date       : 14-Mar-2025", big),
        ("Customer   : Rhea Testerman", big),
        ("", small),
        ("Item   : Kestrel Aero 14 Laptop", big),
        ("         16 GB RAM / 512 GB SSD", big),
        ("Serial : KA14-7731-QX09", big),
        ("", small),
        ("Rate                  74,990.00", big),
        ("GST 18% incl.         11,439.15", big),
        ("TOTAL AMOUNT  INR     74,990.00", bold),
        ("", small),
        ("Warranty : 12 months standard warranty", big),
        ("            from date of purchase", big),
        ("Valid until 13-Mar-2026", big),
        ("Keep this bill for warranty claims.", small),
        ("", small),
        (FOOTER, small, "center"),
    ], dt.datetime(2025, 3, 14, 18, 42, 7), seed=2231, angle=1.8)
    text_page(pics / "IMG_2232_Grocery_Receipt.jpg", [
        ("FRESHNEST SUPERMART (DEMO)", bold, "center"),
        ("Bill No 88213   02-Oct-2026", small, "center"),
        ("", small),
        ("Tomatoes 1 kg           42.00", big), ("Basmati rice 5 kg      610.00", big), ("Toor dal 1 kg          168.00", big),
        ("Amul-style curd 400g     38.00", big), ("Bananas 12 pcs           60.00", big),
        ("TOTAL                   918.00", bold), ("Paid by UPI", small), ("", small), (FOOTER, small, "center"),
    ], dt.datetime(2026, 10, 2, 19, 5, 11), seed=2232, angle=-1.2)
    text_page(pics / "IMG_2233_Restaurant_Bill.jpg", [
        ("SPICE ALLEY KITCHEN (DEMO)", bold, "center"), ("Table 7   Bill 5521   21-Sep-2026", small, "center"), ("", small),
        ("Paneer tikka            320.00", big), ("Veg biryani x2          560.00", big), ("Masala chai x2          80.00", big),
        ("Service charge           96.00", big), ("TOTAL                  1056.00", bold), ("", small), (FOOTER, small, "center"),
    ], dt.datetime(2026, 9, 21, 21, 12, 40), seed=2233, angle=0.9)
    text_page(pics / "IMG_2234_Cab_Receipt.jpg", [
        ("RIDEWAVE CABS (DEMO)", bold, "center"), ("Trip receipt 14-Sep-2026", small, "center"), ("", small),
        ("Pickup : Fictional Nagar Metro", big), ("Drop   : Imaginary Tech Park", big), ("Distance 11.4 km   Time 38 min", big),
        ("Fare INR 312.00", bold), ("", small), (FOOTER, small, "center"),
    ], dt.datetime(2026, 9, 14, 9, 31, 2), seed=2234, angle=-0.7)
    text_page(pics / "IMG_2235_Bus_Ticket.jpg", [
        ("CITYLINK EXPRESS (DEMO)", bold, "center"), ("E-ticket PNR CL77412   Seat 14W", big), ("Bengaluru to Mysuru 06:30", big),
        ("Journey date 03-Oct-2026   INR 450", big), ("", small), (FOOTER, small, "center"),
    ], dt.datetime(2026, 10, 1, 7, 55, 30), seed=2235, angle=1.1)
    no_text_photo(pics / "IMG_2236_Beach_NoText.jpg", dt.datetime(2026, 8, 15, 17, 20, 0), 2236, (0.6, 0.8, 1.0))
    hand = font(HAND, 40)
    text_page(pics / "IMG_2237_Shopping_List_Handwritten.jpg", [
        ("Shopping list", font(HAND, 52)), ("- rice and dal", hand), ("- light bulbs (2)", hand), ("- birthday card for Meena", hand),
        ("- phone cover", hand), ("- coriander", hand), ("", hand), (FOOTER, font(SANS, 24)),
    ], dt.datetime(2026, 10, 5, 8, 2, 14), seed=2237, angle=-2.0, bg=(120, 100, 80))
    text_page(pics / "IMG_2238_Washing_Machine_Warranty_Card.jpg", [
        ("TIDALWASH APPLIANCES (DEMO)", bold, "center"), ("WARRANTY CARD", bold, "center"), ("", small),
        ("Product : TidalWash 7 kg front loader", big), ("Model   : TW-7F-2100", big), ("Serial  : TW7F-5521-LM44", big),
        ("Bought  : 02-Jun-2024 from Bubble Home Store", big), ("Warranty: 24 months on the machine,", big),
        ("          60 months on the motor", big), ("", small), (FOOTER, small, "center"),
    ], dt.datetime(2024, 6, 2, 12, 15, 0), seed=2238, angle=1.4)

    plain(docs / "Weekend_Trek_Packing_List.txt", """
Weekend trek packing list (Fictional Ridge, 18-19 Oct 2026)
Water 3 litres, trail mix, rain jacket, two pairs of socks, torch with spare batteries,
first aid kit, sunscreen, power bank, printed route map, cash INR 500.
Meet at the Imaginary Gate parking at 5:30 am. Bring your own cup.
""", dt.datetime(2026, 10, 6, 21, 0))
    plain(docs / "Book_Club_Reading_List.md", """
# Book club reading list
1. The Lantern Keepers (October)
2. A Map of Small Rivers (November)
3. Salt, Sugar and Smoke - essays on food (December)
Next meeting at Meena's place, last Sunday of the month.
""", dt.datetime(2026, 9, 28, 20, 30))
    plain(docs / "Yoga_Class_Schedule.txt", """
Yoga class schedule at Lotus Loft Studio (DEMO)
Mon and Wed 6:30 am beginners, Tue and Thu 7:00 pm flow, Sat 8:00 am stretch.
Monthly fee INR 2,400, first class free. Mats provided.
""", dt.datetime(2026, 9, 3, 18, 10))
    # the file that must never leave this phone
    slip = docs / "Private" / "Salary_Slip_Sep2026.pdf"
    text_pdf(slip, "Salary slip - September 2026", [
        "**Employer: Quillstone Systems Pvt Ltd (DEMO)", "Employee: Rhea Testerman    Employee ID: QS-00417", "",
        "**Earnings", "Basic salary                        INR 52,000", "House rent allowance              INR 20,800",
        "Special allowance                   INR 24,000", "Reimbursement: laptop and internet bill   INR 3,500", "",
        "**Deductions", "Provident fund                      INR 6,240", "Professional tax                    INR 200",
        "Income tax (TDS)                    INR 2,610", "", "**Net pay for September 2026: INR 91,250",
        "Bank account ending 4471 (synthetic)",
    ])
    stamp_file(slip, dt.datetime(2026, 10, 1, 10, 0))


def phone_a():
    pics = ROOT / "A" / "Pictures" / "SwarmDemo"
    docs = ROOT / "A" / "Documents" / "SwarmDemo"
    big, bold, small = font(MONO, 34), font(MONO_B, 40), font(MONO, 27)
    cert = docs / "Laptop_Extended_Warranty_Certificate.pdf"
    text_pdf(cert, "Laptop extended warranty certificate", [
        "**Brightwell Assurance Co (DEMO)  -  Extended Warranty Programme", "",
        "Policy number: EW-2025-90871", "Policyholder: Rhea Testerman", "Product: Kestrel Aero 14 Laptop (16 GB / 512 GB)",
        "Serial number: KA14-7731-QX09", "Purchase bill: ZEH/25-26/004417 dated 14-Mar-2025, Zorbik Electronics Hub", "",
        "**Coverage", "Extended warranty of 24 months begins when the standard 12-month warranty ends.",
        "Covers motherboard, display, battery and keyboard faults. Accidental damage is not covered.", "",
        "Extended warranty starts: 14-Mar-2026", "**Expiry date: 13-Mar-2028", "Premium paid: INR 6,499", "",
        "Keep the original purchase bill with this certificate when you make a claim.",
        "Claims desk (fictional): 1800-000-0000",
    ])
    stamp_file(cert, dt.datetime(2025, 3, 16, 11, 0))
    text_pdf(docs / "Society_Maintenance_Notice_Oct2026.pdf", "Maintenance notice - October 2026", [
        "Sunflower Residency Welfare Association (DEMO)", "", "Quarterly maintenance of INR 4,500 per flat is due by 31-Oct-2026.",
        "Water tank cleaning on Sunday 18-Oct-2026, supply off 9 am to 1 pm.", "Pay at the office or by UPI to the society account.",
    ])
    stamp_file(docs / "Society_Maintenance_Notice_Oct2026.pdf", dt.datetime(2026, 10, 3, 9, 0))
    scanned_pdf(docs / "Library_Fine_Notice_Scanned.pdf", [
        ("CITY CENTRAL LIBRARY (DEMO)", font(MONO_B, 40), "center"), ("OVERDUE NOTICE", font(MONO_B, 40), "center"), ("", font(MONO, 30)),
        ("Member: Rhea Testerman   Card 20417", font(MONO, 32)), ("Book: A Map of Small Rivers", font(MONO, 32)),
        ("Due 12-Sep-2026   Returned 30-Sep-2026", font(MONO, 32)), ("Fine for 18 days: INR 36", font(MONO, 32)),
        ("", font(MONO, 30)), (FOOTER, font(MONO, 26), "center"),
    ], seed=77)
    stamp_file(docs / "Library_Fine_Notice_Scanned.pdf", dt.datetime(2026, 9, 30, 16, 0))
    docx(docs / "Rental_Agreement_Summary.docx", [
        "Rental agreement summary", "Landlord: Mr Demo Kulkarni (invented). Tenant: Rhea Testerman.",
        "Monthly rent INR 18,500, security deposit INR 55,500.", "Lease runs 1-Jan-2026 to 30-Nov-2026 with a one-month notice period.",
        "Rent is due on the 5th of every month.",
    ])
    stamp_file(docs / "Rental_Agreement_Summary.docx", dt.datetime(2026, 1, 2, 12, 0))
    plain(docs / "Goa_Trip_Itinerary.txt", """
Goa trip itinerary, 12-15 Nov 2026
Day 1: arrive 10 am, check in at Palm Shade Homestay (booking ref PS-3391), evening at the beach.
Day 2: spice farm visit, old market. Day 3: boat ride at 9 am. Day 4: checkout 11 am, flight 6 pm.
""", dt.datetime(2026, 10, 4, 22, 0))
    plain(docs / "Idli_Sambar_Recipe.md", """
# Idli and sambar
Soak 2 cups rice and 1 cup urad dal separately for 6 hours, grind, ferment overnight.
Steam for 12 minutes. For sambar boil toor dal with tamarind, add vegetables and sambar powder.
""", dt.datetime(2026, 8, 9, 8, 0))
    plain(docs / "Notes_Dentist_Appointment.txt", """
Dentist appointment at SmileSquare Clinic (DEMO), Friday 16-Oct-2026 at 4:30 pm. Cleaning and check-up.
Bring previous X-ray. Fee about INR 1,200.
""", dt.datetime(2026, 10, 8, 10, 0))

    text_page(pics / "IMG_1042_Whiteboard_Sprint.jpg", [
        ("SPRINT PLANNING", font(MONO_B, 46)), ("- login screen  (Ravi)", font(MONO, 36)), ("- export report  (Tara)", font(MONO, 36)),
        ("- fix upload bug  (Meena)", font(MONO, 36)), ("Demo on Friday 3 pm", font(MONO, 36)), ("", small), (FOOTER, small),
    ], dt.datetime(2026, 9, 28, 10, 15, 0), seed=1042, bg=(205, 210, 215), angle=-1.0)
    text_page(pics / "IMG_1043_Parking_Ticket.jpg", [
        ("MALLWALK PARKING (DEMO)", bold, "center"), ("Ticket 009812   Bay B2", big), ("In 14:05   Out 16:40", big),
        ("Charges INR 60.00", bold), ("", small), (FOOTER, small, "center"),
    ], dt.datetime(2026, 10, 3, 16, 41, 0), seed=1043, angle=1.6)
    no_text_photo(pics / "IMG_1044_Landscape_NoText.jpg", dt.datetime(2026, 7, 20, 7, 45, 0), 1044, (0.9, 0.7, 0.5))
    text_page(pics / "IMG_1045_Gym_Timetable.jpg", [
        ("IRONLEAF FITNESS (DEMO)", bold, "center"), ("Timetable", bold, "center"), ("Mon  Strength 6 am", big), ("Wed  Cardio 6 am", big),
        ("Fri  Core and mobility 7 am", big), ("Sat  Open floor 8 to 11 am", big), ("", small), (FOOTER, small, "center"),
    ], dt.datetime(2026, 9, 1, 6, 5, 0), seed=1045, angle=-1.5)
    text_page(pics / "IMG_1046_Mobile_Bill_Sept.jpg", [
        ("PINGLINE MOBILE (DEMO)", bold, "center"), ("Monthly bill September 2026", big), ("Plan: 2 GB per day, 84 days", big),
        ("Bill amount INR 599.00", bold), ("Due date 12-Oct-2026", big), ("", small), (FOOTER, small, "center"),
    ], dt.datetime(2026, 10, 2, 8, 30, 0), seed=1046, angle=0.8)


def phone_c():
    pics = ROOT / "C" / "Pictures" / "SwarmDemo"
    docs = ROOT / "C" / "Documents" / "SwarmDemo"
    big, bold, small = font(MONO, 34), font(MONO_B, 40), font(MONO, 27)
    text_page(pics / "IMG_3301_Electricity_Bill.jpg", [
        ("SUNRISE POWER DISTRIBUTION (DEMO)", bold, "center"), ("ELECTRICITY BILL 09/2026", bold, "center"), ("", small),
        ("Account ID : 8829104921", big), ("Meter      : 48821", big), ("Units used : 312", big), ("Due date   : 24-Sep-2026", big),
        ("Amount payable  INR 2,410.00", bold), ("Status: PAID via UPI", big), ("", small), (FOOTER, small, "center"),
    ], dt.datetime(2026, 9, 20, 9, 12, 0), seed=3301, angle=1.3)
    deva = font(DEVANAGARI, 46)
    latin = font(SANS, 40)
    # Latin-only OCR cannot read Devanagari; the note keeps a few Latin tokens so the outcome is a measured one.
    # Pillow here has no complex-text shaping (no raqm), so the text avoids the short-i matra and conjuncts that would render wrongly.
    text_page(pics / "IMG_3302_Hindi_Note.jpg", [
        ("घर के काम", font(DEVANAGARI, 58)), ("लाइट का भुगतान 25 तारीख तक करना है", deva),
        ("दूध वाले को 1200 रुपये देने हैं", deva), ("सीमा जी को फोन करना है", deva), ("Meeting 28-Oct 5 pm", latin), ("", deva), (FOOTER, font(SANS, 24)),
    ], dt.datetime(2026, 10, 6, 7, 40, 0), seed=3302, angle=-1.8, bg=(130, 110, 90))
    text_page(pics / "IMG_3303_Whiteboard_Architecture.jpg", [
        ("QUARTER PLAN", font(MONO_B, 46)), ("Phase 1  data cleanup", font(MONO, 36)), ("Phase 2  new dashboard", font(MONO, 36)),
        ("Phase 3  rollout to teams", font(MONO, 36)), ("Owner: Tara", font(MONO, 36)), ("", small), (FOOTER, small),
    ], dt.datetime(2026, 10, 1, 15, 0, 0), seed=3303, bg=(205, 210, 215), angle=0.7)
    text_page(pics / "IMG_3304_Pharmacy_Receipt.jpg", [
        ("GREENCROSS PHARMACY (DEMO)", bold, "center"), ("Receipt 40127   04-Oct-2026", small, "center"), ("", small),
        ("Vitamin D3 tablets x1      180.00", big), ("Cough syrup 100 ml        95.00", big), ("Bandage roll x2           40.00", big),
        ("TOTAL                     315.00", bold), ("", small), (FOOTER, small, "center"),
    ], dt.datetime(2026, 10, 4, 18, 22, 0), seed=3304, angle=-0.9)
    no_text_photo(pics / "IMG_3305_Mountain_NoText.jpg", dt.datetime(2026, 6, 11, 6, 50, 0), 3305, (0.7, 0.9, 0.8))
    text_page(pics / "IMG_3306_Visiting_Card.jpg", [
        ("TARA FERNSBY", bold, "center"), ("Product Designer, Quillstone Systems (DEMO)", small, "center"), ("tara.fernsby@example.invalid", big, "center"),
        ("+91 00000 00000", big, "center"), ("", small), (FOOTER, small, "center"),
    ], dt.datetime(2026, 8, 30, 12, 0, 0), seed=3306, angle=1.0, width=900)

    plain(docs / "Meeting_Notes_Q4_Planning.txt", """
Meeting notes - Q4 planning, 2 Oct 2026
Attendees: Tara, Ravi, Meena, Rhea.
Decisions: launch the dashboard on 20 Nov, freeze features on 6 Nov, Ravi owns the data migration.
Actions: Tara to share mock-ups by Friday, Meena to book the review room, Rhea to update the roadmap.
Risks: vendor API change could slip the migration by one week.
""", dt.datetime(2026, 10, 2, 17, 30))
    plain(docs / "Wifi_Router_Setup_Notes.md", """
# Router setup notes
Admin page at 192.168.1.1 (fictional). Network name HomeNest-5G, change the default password,
enable guest network, set DNS to automatic. Firmware update every three months.
""", dt.datetime(2026, 7, 14, 20, 0))
    text_pdf(docs / "Broadband_Plan_Terms.pdf", "Broadband plan terms", [
        "Fibrenest Broadband (DEMO)", "", "Plan: 300 Mbps unlimited, INR 899 per month, 12-month commitment.",
        "Installation free. Router rental INR 50 per month. Early exit fee INR 1,500.", "Billing cycle starts on the 8th of each month.",
    ])
    stamp_file(docs / "Broadband_Plan_Terms.pdf", dt.datetime(2026, 5, 8, 10, 0))
    docx(docs / "Society_AGM_Minutes.docx", [
        "Annual general meeting minutes", "Held on 14 September 2026 at the community hall, Sunflower Residency (DEMO).",
        "Agreed: repaint the lobby, install two more charging points, revise the visitor parking rule.",
        "Next AGM in September 2027.",
    ])
    stamp_file(docs / "Society_AGM_Minutes.docx", dt.datetime(2026, 9, 15, 11, 0))
    plain(docs / "Pet_Vaccination_Record.txt", """
Pet vaccination record - Biscuit (beagle, invented)
Rabies booster 12-Aug-2026, next due 12-Aug-2027. Deworming 3-Oct-2026. Vet: Pawfield Clinic (DEMO).
""", dt.datetime(2026, 10, 3, 12, 0))


# ---- adb --------------------------------------------------------------------------------------------------

def adb(serial, *args):
    cmd = [ADB, "-s", serial, *args]
    print("adb", " ".join(f'"{a}"' if " " in a else a for a in cmd[1:]))
    r = subprocess.run(cmd, capture_output=True, text=True)
    tail = (r.stdout.strip().splitlines() or [""])[-1]
    if r.returncode != 0:
        print("  FAILED:", (r.stderr or r.stdout).strip()[:300])
    elif tail:
        print("  ", tail)
    return r.returncode


def push(selected):
    for label in selected:
        serial = PHONES[label]
        base = ROOT / label
        print(f"--- Phone {label} ({serial})")
        subprocess.run([ADB, "connect", serial], capture_output=True)
        for sub in ("Pictures", "Documents"):
            src = base / sub / "SwarmDemo"
            if not src.exists():
                continue
            adb(serial, "shell", "mkdir", "-p", f"/sdcard/{sub}/SwarmDemo")
            adb(serial, "push", str(src) + os.sep + ".", f"/sdcard/{sub}/SwarmDemo/")
        # make the pushed pictures visible to MediaStore (photos are indexed through it)
        adb(serial, "shell", "content", "call", "--uri", "content://media/", "--method", "scan_volume", "--arg", "external_primary")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--push", action="store_true", help="adb push the corpus to the phones after generating")
    ap.add_argument("--phones", default="ABC", help="which phones to push to, e.g. AB")
    args = ap.parse_args()
    for p in ("A", "B", "C"):
        import shutil
        shutil.rmtree(ROOT / p, ignore_errors=True)
    phone_a()
    phone_b()
    phone_c()
    for p in ("A", "B", "C"):
        files = sorted(f for f in (ROOT / p).rglob("*") if f.is_file())
        print(f"{p}: {len(files)} files")
        for f in files:
            print("   ", f.relative_to(ROOT / p).as_posix(), f"{f.stat().st_size // 1024} KB")
    if args.push:
        push(list(args.phones))
    else:
        print("\nTo push: python scripts/make-demo-corpus.py --push   (adb commands are printed as they run)")


if __name__ == "__main__":
    sys.exit(main())
