# The model proposes, Java checks, and a person saves

The app asks a language model for things a person could have typed themselves: the first is a rule, suggested from a sentence. What the model answers is never taken for a fact or for a change. It is read as a fixed structure, checked in Java by the same code that checks what a person enters, and shown to its owner, who saves it or does not. The model is OpenAI's, reached with Spring AI, through one class that every request goes through.

What holds for every request:

- It is one request: a time to answer in, no second try, and a limit on how much the model may say.
- What a person typed goes to the model as a JSON value beside its instructions, and is never part of them.
- Nothing that is sent to the model or received from it is written to the log.
- Without a key the app starts and runs, and says that the model cannot be asked.

## Considered Options

- **Letting the model's answer be saved as it comes.** A model that is wrong or that was talked into something would then change a catalog. Checked and shown first, the worst a wrong answer does is to fill a form in with something its owner does not save.
- **Putting what a person typed into the instructions.** Then a sentence could give the model instructions of its own. As data beside them it still can try, and what comes of it is checked like any other answer.
- **OpenAI's HTTP API with the client the backend already has.** One request and one answer are a few dozen lines that way, and Spring AI's client brings 61 MB of libraries, 51 of them OpenAI's own SDK. Spring AI was taken for what comes next: tools the model may call, which it runs and to which it hands the signed-in person without telling the model.
- **Spring AI's starter, which makes the client from the app's properties.** It fails to start without a key. The app makes the client itself, and makes none when there is no key.

## Consequences

- The model's name, its address, and the time it is given are configuration: `gpt-6-luna`, as OpenAI lists it on 2026-10-09, and twenty seconds.
- The model is asked with its reasoning effort set to `none`. What it may say is then what it does say, so the limit on its output is a limit on its cost, and OpenAI's page for the model says that calling tools over Chat Completions needs it.
- Spring AI's client takes a request's time to answer in from that request's own options, so the one class gives it with each request.
- A check that a rule entered by hand passes through is the check a suggestion passes through. A new check is written once.
- The tests reach a stand-in and never OpenAI. What the real model makes of real sentences is not something they show.
