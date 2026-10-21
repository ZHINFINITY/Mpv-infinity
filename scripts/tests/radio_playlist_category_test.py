from pathlib import Path
ROOT=Path(__file__).parents[2]
policy=(ROOT/'app/src/main/java/app/infinity/mpvz/utils/media/RadioM3uPlaylistPolicy.kt').read_text()
repo=(ROOT/'app/src/main/java/app/infinity/mpvz/database/repository/PlaylistRepository.kt').read_text()
ui=(ROOT/'app/src/main/java/app/infinity/mpvz/ui/browser/sheets/PlaylistActionSheet.kt').read_text()
screen=(ROOT/'app/src/main/java/app/infinity/mpvz/ui/browser/playlist/PlaylistScreen.kt').read_text()
card=(ROOT/'app/src/main/java/app/infinity/mpvz/ui/browser/cards/PlaylistCard.kt').read_text()
assert 'duration < 0' in policy and 'isMusicPlaylist' in policy
assert 'forceRadio' in repo and 'RadioM3uPlaylistPolicy' in repo
assert 'Add Radio M3U' in ui and 'radioImport' in ui
assert 'ordinaryPlaylists' in screen and 'radioPlaylists' in screen
assert 'Playlists' in screen and 'Radio stations' in screen
assert 'stations' in card and 'isRadioM3u' in card
assert 'removePrefix("Radio/M3U ")' in card
print('radio playlist category source contract: ok')
