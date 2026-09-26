# Depart

Traffic-aware "when to leave" alarm for Android.

You never need Android Studio for this. GitHub builds the app in the cloud every time you push,
and the app on your phone offers the update.

## One-time setup (about 15 minutes)

1. Make a free account at github.com and install **GitHub Desktop** (desktop.github.com).
2. On github.com, click **New repository**. Name it `depart`, choose **Public**, and create it.
   It must be public so the phone can see new versions. Nothing private is in this code:
   your TomTom key lives only on your phone, and the signing key goes in secrets (next step).
3. In the new repo: **Settings > Secrets and variables > Actions > New repository secret**.
   Add the 4 secrets from `depart-signing-secrets.txt` (the separate file, not part of this folder).
4. In GitHub Desktop: **File > Clone repository**, pick `depart`, and clone it to your laptop.
   Copy everything inside this `Depart` folder into the cloned folder
   (including the hidden-looking `.github` folder).
5. In GitHub Desktop, type a summary like "First version", click **Commit to main**, then **Push origin**.
6. On github.com open the **Actions** tab. The build takes about 5 to 8 minutes.
   If it failed because the secrets weren't added yet, add them and press **Re-run all jobs**.
7. Open the **Releases** section of the repo on your phone, download `Depart-N.apk`, and install it.
   Android will ask you to allow installs from your browser once.

## In the app

1. **Settings > Setup**: tap each **Allow** until every dot is green.
2. **TomTom key**: tap *Get a free key*, sign up (no card), copy the key from your dashboard, paste, save.
3. **Home and Office**: stand at home, tap **Save where I am now** (it's named Home automatically).
   Do the same at the office. These are exact map points, so navigation always opens the right place.
4. Plan your trips. Pick places from the list so each has a map point.
5. Test: **Test alarm** and **Test call screen** in Settings, and **Check now** at the top of Today
   for a live traffic reading.

## Sending an update

Replace the changed files in your cloned folder, then in GitHub Desktop: **Commit** and **Push**.
About 6 minutes later, the app shows **Update ready**. Tap **Install**, then **Update**.
Your trips and settings stay.

## If alarms come late (Samsung, Xiaomi, Oppo, Vivo, OnePlus)

These phones kill background apps aggressively. Besides "No battery limits" in Setup:
- Samsung: Settings > Battery > Background usage limits > Never sleeping apps > add Depart.
- Xiaomi/Redmi/POCO: App info > Autostart on, Battery saver > No restrictions.
- Oppo/Realme/Vivo: App info > Battery > Allow background activity, and allow Auto launch.

## How it saves battery

Location isn't left running. At each traffic check (every 15 minutes, starting 1 hour before
your usual leave time) Depart asks for one location fix, which takes a few seconds, sends one
traffic request, and lets go. Location stays on only while you drive, and turns off within
200 m of your destination (or 90 minutes after your expected arrival, as a safety net).
