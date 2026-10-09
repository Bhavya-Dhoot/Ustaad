Bundled fonts for the SwarmOS "Whiteboard" design system (docs/design/DESIGN.md section 3).
The font files themselves are in res/font (Android refuses any non-font file in that folder,
so this record lives in assets/fonts instead; assets are packaged in the APK, which also keeps
the licence texts shipping with the fonts as the OFL requires).

All four are variable TTFs from the google/fonts repository, SIL Open Font License 1.1
(https://openfontlicense.org). Downloaded 2026-10-10 from
https://raw.githubusercontent.com/google/fonts/main/ofl/<folder>/<file>

res/font file            family               source (ofl/ folder and file)                                   axes used           licence text here
bricolage_grotesque.ttf  Bricolage Grotesque  bricolagegrotesque/BricolageGrotesque[opsz,wdth,wght].ttf       wght 600-800, opsz  OFL-BricolageGrotesque.txt
geist.ttf                Geist                geist/Geist[wght].ttf                                           wght 400-700        OFL-Geist.txt
geist_mono.ttf           Geist Mono           geistmono/GeistMono[wght].ttf                                   wght 400-700        OFL-GeistMono.txt
shantell_sans.ttf        Shantell Sans        shantellsans/ShantellSans[BNCE,INFM,SPAC,wght].ttf              wght, INFM          OFL-ShantellSans.txt

Roles: Bricolage Grotesque = titles, Geist = body and labels, Geist Mono = every number, file name, model
name and stamp, Shantell Sans = handwritten notes. Files are renamed to lower case with underscores because
Android resource names allow nothing else. Total size about 2 MB.
