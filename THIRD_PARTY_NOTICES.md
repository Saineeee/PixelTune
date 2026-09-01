# Third-Party Notices

This file records third-party material whose design or code was adapted into
PixelTune, together with the license terms that applied at the time of the
adaptation.

## PixelPlayer — state slicing approach (commit 22f8349f)

The screen-level state-slicing approach used by the Search and Library
screens (observing a narrow, immutable slice of the player UI state instead
of collecting the full aggregate, so screens only recompose when their own
inputs change) is adapted from PixelPlayer commit `22f8349f`, authored
2026-04-12. At that date PixelPlayer was distributed under the MIT License,
which is reproduced below as it applied to that commit:

```
MIT License

Copyright (c) 2024-2026 Theo Vilardo and PixelPlayer contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

Scope note: only the *approach* described above derives from that commit.
The implementation in this repository was written for PixelTune's own
architecture, package layout and naming, and does not reproduce upstream
code, comments or identifiers.
