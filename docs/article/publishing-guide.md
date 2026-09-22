# Publishing the article

Publication status is recorded below. Prepared files alone do not establish publication or employer clearance.

## Zenodo and GitHub Pages

The report is published on Zenodo: [10.5281/zenodo.22886052](https://doi.org/10.5281/zenodo.22886052). The PDF in this repository is the archived report; the HTML and Markdown copies now also link to that published version. The first-publication date is September 20, 2026.

For GitHub Pages, merge the article update, then select **Settings > Pages > Deploy from a branch > master > /docs** and save. `docs/.nojekyll` serves the prebuilt files without Jekyll conversion, preserving the article's links to Markdown references and JSON evidence.

After deployment, verify the article at `https://viktorkhudiaiev.github.io/demo_security_module/article/`, including the disclaimer, the final DOI link, and the linked references. This is the expected address, not confirmation that Pages has been enabled or deployed. The root page is the existing interactive walkthrough.

Rebuild the reading copies with `node docs/build/build-reading-views.mjs`; rebuild the offline archive with `python docs/build/package-materials.py` and run `python docs/build/validate-content.py`. Do not regenerate the archived PDF merely to add the DOI footer to the web article.

## LinkedIn: text plus PDF

1. Open the [formatted LinkedIn preview](linkedin-post.html) and select **Copy post with line breaks**. Paste into a new LinkedIn post. The plain [text file](linkedin-post.txt) is also available. Blank lines are part of the copied text; LinkedIn controls its own font and exact spacing.
2. Attach [article.pdf](article.pdf) as a document. Use the title **Verifiable Record Integrity Without a Blockchain**.
3. Review the post and document preview. The post refers to the attached article and needs no placeholder URL.
4. Publish only after completing any employer clearance required for this version.

The PDF contains the full article, its employer disclaimer and public HTTPS reference links. Do not upload the documentation ZIP as the article. A local filesystem path or localhost URL is not a link other readers can open.

The [cover image](assets/record-integrity-cover.png) is a conceptual illustration, not an architecture diagram or test result. Use it as a Medium cover or for a LinkedIn image post accompanied by a public article link. Alternatively, use the PDF document-post format. The composer may not allow an image and PDF in the same post; choose the appropriate format rather than assuming both attachments are supported.

The preview uses the same text as the plain file and copies text only. Do not copy the entire preview page, which also includes publishing instructions. No Unicode imitation-bold alphabet or invisible spacing characters are required.

## Medium: title and article body

1. Open [medium-article.html](medium-article.html) in a browser.
2. Use the first heading as the Medium story title and the next heading as its subtitle.
3. Copy the article body beginning with the author line into the Medium editor. Keep the disclaimer near the beginning. The reading page contains only article content, not site navigation or private notes.
4. Check headings, lists and links in Medium's preview. The export converts tables into labeled lists so their content does not depend on table support.
5. Review and publish only after clearance. After publication, copy the real public story URL if you want to share a link instead of attaching the PDF.

[medium-article.md](medium-article.md) is the matching Markdown source for editing or transfer. It is generated from the same canonical article as the PDF and normal HTML; do not maintain a separate factual version by editing generated exports.

The Medium title is **If Someone Changes Your Database, Should Your System Still Execute the Payment?** The body and dated evidence remain the same as the technical article. Medium uses article text, not a PDF as the story body.

## Consistency and publication rights

The canonical source is [article.md](article.md); [index.html](index.html), PDF and Medium exports are reading formats. Source-reference links in the portable PDF and Medium versions point to published snapshot `9afdac2fc062241ee646742fa3179a83cc4b0b99`. That snapshot identifies the supporting references, not a claim that this September 20 article revision has been pushed to GitHub.

The publication date changed for the disclaimer update. Implementation review and measurement dates did not change. This is not a new test run, benchmark or production certification. Historical files and Git history are not rewritten.

If submitting a separate article to an editorial outlet, disclose existing public versions and actual AI assistance. Check originality and exclusivity requirements before cross-posting. Medium supports a canonical link to an existing original publication; set one only when there is a real public source URL.

Sources checked September 20, 2026:
- [Medium: setting a canonical link](https://help.medium.com/hc/en-us/articles/360033930293-Set-a-canonical-link).
- [InfoQ: originality, exclusivity and AI disclosure](https://www.infoq.com/guidelines/).
