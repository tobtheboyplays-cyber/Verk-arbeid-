# Bannerhold source transfer
The full source snapshot is uploaded in 32 verified bundle parts. The import workflow creates branch `codex/bannerhold-qa-handoff-20260926` with ordinary source files, QA summaries and Claude continuation context.

If the workflow is unavailable, a cloud agent can reconstruct it:
```sh
cat transfer/bannerhold.part-* > /tmp/bannerhold.bundle
echo '9a7659b225e9d4b45b48b4260b3956764954101d1ceee867977537ba529ec720  /tmp/bannerhold.bundle' | sha256sum -c -
git clone /tmp/bannerhold.bundle /tmp/bannerhold-work
cd /tmp/bannerhold-work
cat HANDOFF_START_HERE.md
```
Source snapshot: 1db9168b7dab4b1ea0b9c83e51aee7440c132499. Latest source JUnit: 1150 run, 1144 pass, 6 fail. Earlier full GameTests: 2091 run, 208 required failures (192 generated blueprint construction, 16 other). QA remains ongoing. Source and reports are not release-ready. No real worlds or raw Claude transcripts are included.
