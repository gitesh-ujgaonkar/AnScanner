AnScanner
A mobile scanner application that lets you scan any document with zero annoying popup ads and no watermarks.

One day, I needed to scan some documents and, like anyone would, I searched the Play Store and downloaded a free scanner app. But the app was filled with ads—after every click, before scanning, and before saving, full-screen popup ads kept interrupting me. That was already frustrating, but the final straw was finding out that after scanning, the app slapped its own watermark at the bottom of every single page. It didn't look professional at all.

That's when I decided to build my own scanner app: one without those intrusive popup ads (I might only include subtle banner ads at the top or bottom), zero watermarks, and a clean, intuitive UI.

Today (12-09-2026), I am officially starting work on this project! I'll post updates here until it's finished and keep everything documented along the way. If you're a developer and interested in this idea, feel free to reach out to me through any of the links on my profile. I’d love to team up, collaborate, and learn together.

12-09-2026 7:35 (GMT+5:30) :
So after working on it for whole day, i was able to create the first draft, so the application is succesfully installed on my device, and it is able to click pics and create pdf from it, Toast, sliding through pages, and all the buttons are working as intended, though the edge detection is not working/working not good enough, so need to check that. 
For development, I Researched and discussed with Gemini, and used Antigravity to define everything and make the baase version. Next i wanna add firebase for anlytics and maybe make the app publish level. Maybe ill add ads on it as banner ads and not fullscrren. 
In Process, i faced some issues reagding package icon and missing string values, which i fixed easily, the majority of time i spend was a unrelated issue( not even issue), so the gradle wasnt getting downloaded at all cause of net speed nd it kept timing out or network issue and had to restart, it took me about 2.5 hours to deal with it. 


15-09-2026 9:19 (GMT +5:30) :
I have integrated firebase for anylytics and feedback mechanism which lets users send feedback, it works succesfully and i can see the feedbacks in the firebase firestore db, as well i have added a crash report sending with users consent, but once the app crashes the ui threat is killed instantaly so i can not show po up for consent on crash, instead i am showing the pop up on the next launch.
Also I have added pdf view support, where i can view pdf and also edit.
As well i enhanced some of the excisting features to make them work as intended. now i have applied for play console and once it is done then i plan to publish the app and then will fix more things with updates.
