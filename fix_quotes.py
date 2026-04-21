import pathlib

target = pathlib.Path(r"d:\桌面\论文\code\-code\frontend\src\views\DataManage.vue")
content = target.read_text(encoding="utf-8")

# Replace smart/curly double quotes with straight double quotes
content = content.replace("\u201c", '"').replace("\u201d", '"')
# Replace smart/curly single quotes with straight single quotes
content = content.replace("\u2018", "'").replace("\u2019", "'")

target.write_text(content, encoding="utf-8")
print("Done. All smart quotes replaced with straight quotes.")
