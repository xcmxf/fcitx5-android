# FUTO Swipe plugin notices

This APK is a separately installed plugin. Its decoder is not linked into the
Fcitx5 for Android APK; the two applications communicate solely through the
documented Android Binder contract.

## FUTO Swipe library — GPL-3.0-or-later

The plugin packages `futo-swipe-release.aar` from
[`android-libs` at `be4362cf82022e613cd34ff95d579c8502eaad8e`](https://gitlab.futo.org/keyboard/android-libs/-/tree/be4362cf82022e613cd34ff95d579c8502eaad8e).
Its corresponding source is the [FUTO Swipe library at
`1b13f2c85d6b347f6ea3fbc4b3aaf01fce42429a`](https://gitlab.futo.org/keyboard/swipe-library/-/tree/1b13f2c85d6b347f6ea3fbc4b3aaf01fce42429a),
licensed under GPL-3.0-or-later. The plugin source, including its `ITrie`
dictionary adapter, is therefore GPL-3.0-or-later as well.

## FUTO Swipe model and English word list — FUTO Source First License 1.1-kb

The build downloads the pinned model revision
[`07ddb48ca68eee2be29b071c71d654f0e7bb126a`](https://huggingface.co/futo-org/futo-swipe/tree/07ddb48ca68eee2be29b071c71d654f0e7bb126a)
and the pinned FUTO Keyboard word list revision
[`007394af28bb72ad70420143b08aba2d74e0e790`](https://github.com/futo-org/android-keyboard/tree/007394af28bb72ad70420143b08aba2d74e0e790).
Their license texts are included in the generated plugin assets. Distributing
the plugin must comply with that license, including its free, non-commercial
distribution condition.

## Fcitx / libime Pinyin lexicon data

The build also downloads
[`dict-20260430.tar.zst`](https://download.fcitx-im.org/data/dict-20260430.tar.zst)
from the official Fcitx data archive and derives the plugin's
`futo-swipe/vocabs/pinyin.combined` from its `dict_sc.txt` and
`dict_extb.txt` sources. This keeps the plugin's Chinese swipe vocabulary in
sync with the same upstream lexicon family used by Fcitx/libime.
