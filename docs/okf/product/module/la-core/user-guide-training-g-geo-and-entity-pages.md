---
type: Guide
title: "Inspecto LA training videos — G · Geo and entity pages (Standard): narration scripts"
description: "The spoken script of the training episodes in group G (Geo and entity pages (Standard)), scene by scene, with the action on screen and the change it causes. Format and status: the overview, user-guide-training-scripts.md."
resource: tools/render-la-demo-video.mjs
tags: [module, la-core, link-analysis, user-guide, training, video]
timestamp: 2026-11-16T00:00:00Z
---

# Inspecto LA training videos — G · Geo and entity pages (Standard)

> Narration scripts for group G of the Inspecto LA training series. How to read an episode, the voice and the
> renderer: [the overview](user-guide-training-scripts.md). Draft, not recorded, not reviewed by product; `[check in UI: …]` marks a detail to
> confirm on the live product before recording.

## Episodes in G

| Episode | Title | Edition | Scenes | Spoken words | UI checks open |
|---|---|---|---|---|---|
| [G1](#g1_the-geo-map) | The Geo map | Standard | 11 | 804 | 6 |
| [G2](#g2_geo-intelligence-and-map-to-graph) | Geo intelligence and pivoting between map and graph | Standard | 10 | 779 | 5 |
| [G3](#g3_entity-profiles) | Entity profiles | Standard | 11 | 727 | 8 |

## G1_The-Geo-map

> **G1 · The Geo map** · Standard · Other Examples Space · the mule ring across Europe · 6 min · user guide §12;
> Geo map guide (`module/geo-link/user-guide-geo-map.md`)

- **Objectives:** draw a Dataset's coordinates as points, and two coordinate pairs as routes; narrow the map by type, by
  the view and by a polygon; measure, search around a spot and pin a note; save, reuse and export a map.
- **Before:** a fresh unzip of `la-standard.zip`, the Other Examples Space, signed in as Demo Analyst, the Geo page empty.
  The Space ships three saved maps: *Where the accounts are*, *The ring across Europe*, *Cash-out through one till*.
- **Covers (plan §5):** 5.5 Geo: the query form (`Source`: Locations (from a Dataset), Routes (origin → destination);
  Dataset; Latitude / Longitude column; Origin / Destination latitude and longitude; Optional columns: Entity column,
  Type column, Time column, Origin name, Destination name; Run), Saved views ▸ Load view, Edit the query, Filter point
  types, Search points, Find place, Fit to data, Heatmap, Filter to view, Data, point and route details, Investigation
  tools (Measure, Radius, Polygon, Note, Clear drawings), Layers (Place labels, Country boundaries, Point labels, Add
  GeoJSON overlay), Clear search & filters, Save view, Import / export saved views, Export (PNG image, GeoJSON).
- **Try it:** load *Cash-out through one till*. In which city is the till the money leaves through, and in which city is
  the hub that funds the accounts? *Answer: the till is in Paris; the funding hub is in Marseille (from the saved map's
  description and data).*
- **Check:** what is the difference between Filter to view and a polygon? *Answer: Filter to view keeps the points inside
  the screen's current rectangle; a polygon keeps the points inside any shape you click, corner by corner.*
- **Gotchas:** the Geo map is Standard and up. These maps carry no time column, so no timeline shows (G2 has one). The map
  reads at most the Space's point cap (5,000 by default) and says *Truncated* above it. Placements are synthetic, at city
  level. If the account column is masked in this Space, labels read as aliases: say what the screen shows.

### S01 · Where, not only who
- Voice: This is Inspecto LA training, episode G1: the Geo map. A graph shows who is linked to whom. Some questions are about where: where does the cash go in, which city does the money pass through, where does it come out? The Geo map draws a Dataset's coordinates as points, or as routes from one place to another, and gives you tools to narrow, measure and mark them. We are in the Standard edition, in the Other Examples Space, on the laundering ring of episode A1.
- Action: intro card.
- Impact: none.

### S02 · An empty map, and a saved one
- Voice: In the rail, open Geo. The page says what it needs: pick a Dataset, map its latitude and longitude columns, then run the query. Nothing is drawn until you ask. The quickest start is a saved map. Open Saved views, choose Where the accounts are, and Load view. Every account in the transfer data, placed in its city, and coloured by the kind of site: small rural branches, where the cash goes in, and tills, where it comes out.
- Chip: Geo · Saved views · Load view
- Action: rail ▸ Geo ▸ hold on *No map yet* ▸ Saved views (folder icon) ▸ *Where the accounts are* ▸ Load view. [check in UI: whether the menu lists the view by its title or its id `mule_account_locations_map`]
- Impact: before/after hold: the empty state → the points across Europe; mark the colour legend.

### S03 · The query behind a map
- Voice: Every map is a query you can read. Press the line at the top, Edit the query. The first picker says what to draw: here, locations from a Dataset. Then the Dataset, and which columns hold latitude and longitude. Under Optional columns: the column that names each point, the one that colours it by type, and a time column, which this Dataset does not have. Change anything, and press Run.
- Chip: Edit the query · Run
- Action: Edit the query ▸ point at `Source` (Locations (from a Dataset)), Dataset `mule_account_locations_dataset`, Latitude column `LAT`, Longitude column `LON` ▸ Optional columns ▸ Entity column `ACCOUNT`, Type column `SITE_TYPE`, Time column (blank) ▸ Run.
- Impact: mark each field as the voice names it.

### S04 · Reading the points
- Voice: Narrow it by kind. Filter point types: untick everything but the rural branches. Now click one point. Its details give the coordinates and what is nearby. Search points finds an account by name, Find place flies to a city, and Fit to data brings everything back into view. The Data button opens the table under the map: one row per point, and clicking a row finds it on the map.
- Chip: Filter point types · Search points · Fit to data · Data
- Action: Filter point types ▸ keep the rural branch kind ▸ click a point ▸ close ▸ Search points ▸ type an account ▸ Find place `Paris` ▸ Fit to data ▸ Data ▸ click a row. [check in UI: the site-type values in the filter]
- Impact: before/after hold: only the rural branches remain; mark the point's details, then the row it found.

### S05 · Density and the view
- Voice: Hundreds of points on top of each other are hard to read. Press Heatmap: the map shows density instead of dots, so the busiest places glow. Press it again for markers. Now zoom to the Low Countries and press Filter to view: only the points inside the screen are kept, in the map and in the table. Clear search and filters undoes every narrowing at once.
- Chip: Heatmap · Filter to view · Clear search & filters
- Action: Heatmap ▸ hold ▸ Heatmap ▸ zoom to the Netherlands and Belgium ▸ Filter to view ▸ hold ▸ Clear search & filters.
- Impact: before/after hold: markers → heatmap; then the region filter; mark the table's shorter count.

### S06 · Routes
- Voice: Load the saved map The ring across Europe. Now the first picker says routes, origin to destination, and the form asks for two pairs of coordinates and the names at each end. Each line is money moving between two accounts, coloured by its role in the ring. Cash paid in at small-town branches gathers in one hub in Rotterdam, fans out to relays in Madrid and Warsaw, and leaves for George Town. A second loop of shell companies circles Valencia, Milan, Lyon and Turin. Click a route for its from, to, distance and movements.
- Chip: Routes (origin → destination)
- Action: Saved views ▸ *The ring across Europe* ▸ Load view ▸ Edit the query (point at Origin latitude, Origin longitude, Destination latitude, Destination longitude, Origin name, Destination name, Type column `ROLE`) ▸ close ▸ click the Rotterdam → Madrid route. [check in UI: the route detail rows From, To, Distance, Movements]
- Impact: mark the hub's star of routes; then the route details.

### S07 · Measure, radius, polygon, note
- Voice: Open Investigation tools. Measure: click the hub, then the relay in Madrid, and the path's length appears. Radius: set a radius, click a spot, and the points within it are counted and kept. Polygon: click the corners of a shape, around the Iberian peninsula, and close it to keep only what is inside. Note: type a note, suspected relay, and click the map where it belongs. Clear drawings removes all of them.
- Chip: Investigation tools · Measure · Radius · Polygon · Note
- Action: Investigation tools ▸ Measure ▸ click Rotterdam, then Madrid ▸ Radius ▸ Radius (m) `50000` ▸ click Rotterdam ▸ Polygon ▸ click 4 corners around Spain and Portugal ▸ Close polygon (4 corners) & filter ▸ Note ▸ Note text `suspected relay` ▸ click Madrid ▸ Clear drawings. [check in UI: the *Path:* line and *N points within 50 km*]
- Impact: mark the Path line, the radius count, the polygon filter and the note pin.

### S08 · Layers
- Voice: Layers decides what is drawn under and over the data. Place labels and Country boundaries help you read the map; Point labels names every point, which is clear on a few and noisy on many. Add GeoJSON overlay lays your own shapes on top: sales regions, a branch's catchment area, a border zone. The file stays in your browser.
- Chip: Layers · Add GeoJSON overlay
- Action: Layers ▸ untick and tick Country boundaries ▸ tick Point labels ▸ point at Add GeoJSON overlay. [check in UI: whether a GeoJSON overlay is kept only in the browser]
- Impact: before/after hold: point labels on; mark the overlay button.

### S09 · One till
- Voice: Load the third map, Cash-out through one till. This is how the money leaves. Look at the shape before you read a single label: many routes converging on one point, beside the ordinary tills in other cities, which each have a few. A star like that is worth a question, and the next episode shows how to turn what the map shows into a graph.
- Chip: Saved views · Load view
- Action: Saved views ▸ *Cash-out through one till* ▸ Load view ▸ hold.
- Impact: mark the converging star.

### S10 · Save, share and export
- Voice: A map worth keeping is saved. Press Save view, give it a name and a description, and save. It joins the list under Saved views, for everyone in this Space who can read the Dataset. Import or export saved views moves them between Spaces as a file. And Export gives you the map itself: a PNG image for a report, or GeoJSON, the points and routes as data, for another tool.
- Chip: Save view · Export · PNG image · GeoJSON
- Action: Save view ▸ Name `ring_iberia` ▸ Description ▸ Save view ▸ Saved views (the new entry) ▸ point at Import / export saved views ▸ Export ▸ PNG image. [check in UI: who else sees a saved Geo view]
- Impact: mark the new saved view; then the export menu.

### S11 · Try it
- Voice: So: a map is a query of points or routes; narrow it by type, view, radius or shape; measure and note; save and export it. Your turn. Load Cash-out through one till. In which city is the till the money leaves through, and in which city is the hub that funds the accounts? The answer is in the description. Next, in G2, geo intelligence, and moving between the map and the graph.
- Action: outro card: *Try it* and *Next: G2 · Geo intelligence*.
- Impact: none.

## G2_Geo-intelligence-and-map-to-graph

> **G2 · Geo intelligence and pivoting between map and graph** · Standard · Insurance Space · the staged-accident junction ·
> 5 min · user guide §6.3, §12; Geo map guide (`module/geo-link/user-guide-geo-map.md`)

- **Objectives:** map an entity and a time column so the Geo intelligence tools can run; play events over time; say the
  question Frequent locations, Stay points and Co-location each answer, and when they come back empty; open co-location
  pairs as a graph; say how a record moves between the map and the graph.
- **Before:** a fresh unzip of `la-standard.zip`, the Insurance Space, signed in as Demo Analyst, the Geo page. The Space
  ships three saved maps; this episode uses *staged_accident_junction_map* (accidents between 02:00 and 03:59 with no
  police report).
- **Covers (plan §5):** 5.5 Geo intelligence (Co-location, Frequent locations, Stay points, Radius (m), Window (min), Min
  dwell (min), Analyze, View as graph and the *Who met whom* graph), the timeline (Play events over time, the time
  filter), Optional columns ▸ Entity column, Time column; user guide §6.3 the graph and map buttons.
- **Try it:** in the same map, map *city* as the Entity column, set Co-location to a radius of 400 m and a window of 60
  minutes, and Analyze. Why is the list empty? *Answer: co-location pairs two different entities in the same place within
  the window. With the city as the entity, every junction accident is one entity, Derby, and the only other entity,
  Exeter, is far away.*
- **Check:** which tool answers "where does this phone keep turning up"? *Answer: Frequent locations.*
- **Gotchas:** the tools need both an entity and a time column mapped, and say so until they are. The shipped Spaces carry
  no tracks (a phone or a vehicle read many times), so Stay points and a one-hour Co-location find nothing here: the
  voice says why. The night map holds 14 accidents, 11 at the junction in Derby and 3 in Exeter (from the shipped
  `accident_location` file). No shipped Geo Dataset carries a `caseId`, `incidentId` or `objectId` column, so the
  map-to-graph button cannot be shown on these zips: S09 says so on screen.

### S01 · Three questions about places
- Voice: This is Inspecto LA training, episode G2: geo intelligence. Once a map knows who each point is and when it happened, it can answer three questions. Where does one thing keep turning up? Where did something stay, and for how long? And who was in the same place at the same time? We will ask each one, see when it has an answer and when it cannot have one, and turn a result into a graph. We are in the Standard edition, in the Insurance Space, on the staged accidents of episode C1.
- Action: intro card.
- Impact: none.

### S02 · A junction at night
- Voice: Open Geo, and load the saved map of the staged accident junction. It shows accidents between two and four in the morning with no police report. There are only fourteen, and eleven of them sit on top of each other, at one junction on the edge of Derby. The other three are in Exeter. Nine of the eleven are the claims of the ring from C1. Zoom in on the stack.
- Chip: Saved views · Load view
- Action: Geo ▸ Saved views ▸ *staged_accident_junction_map* ▸ Load view ▸ zoom on Derby. [check in UI: 14 points, 11 at the junction; the voice drops the numbers if the map differs]
- Impact: mark the stack of eleven, then the three in Exeter.

### S03 · Who, and when
- Voice: Open Geo intelligence. It says what it needs first: these tools need the entity and time columns mapped. A point must say who it is and when it happened. Edit the query, open Optional columns, set the Entity column to the city, and the Time column to when the accident happened. Run. The tools are ready, and a timeline appears under the toolbar.
- Chip: Geo intelligence · Entity column · Time column
- Action: Geo intelligence ▸ hold on *These tools need the entity and time columns mapped* ▸ Edit the query ▸ Optional columns ▸ Entity column `city` ▸ Time column `accident_at` ▸ Run ▸ Geo intelligence.
- Impact: mark the message, then the toolbox's three tools and the timeline.

### S04 · Play it over time
- Voice: Press Play events over time. The accidents appear in the order they happened. At the junction, it is one night every few weeks, from September 2025 to August 2026, always the same spot. Drag the two ends of the slider to keep only a stretch of time; the map and the tools follow it. A pattern in time and place together is harder to explain away than either alone.
- Chip: Play events over time
- Action: Play events over time ▸ let it run ▸ Pause ▸ drag the time filter's start to 2026-01-01 ▸ drag it back.
- Impact: mark the points appearing night by night; then the slider range.

### S05 · Frequent locations
- Voice: The first question: where does one thing keep turning up? Choose Frequent locations, a radius of four hundred metres, and press Analyze. For each entity, here each city, it groups the sightings within the radius and lists the places seen more than once, busiest first. Derby comes first: one spot, again and again. Click it, and the map selects those points. For a phone or a vehicle, this is where it sleeps, works, or meets.
- Chip: Frequent locations · Radius (m) · Analyze
- Action: Frequent locations ▸ Radius (m) `400` ▸ Analyze ▸ click the Derby row. [check in UI: the row *Derby · N sightings at 52.95…, -1.52…*; the voice may name N]
- Impact: mark the Derby row and the selected stack.

### S06 · Stay points need a track
- Voice: The second question: where did something stay? Stay points walks one entity's sightings in time order and finds where it stayed within the radius for at least the minimum dwell. That needs something that moves and is read many times: a phone, a vehicle, a card. Set the Entity column to the accident itself and Analyze. No dwells: each accident is a single reading. An empty answer that says why is still an answer.
- Chip: Stay points · Min dwell (min)
- Action: Edit the query ▸ Entity column `accident_id` ▸ Run ▸ Geo intelligence ▸ Stay points ▸ Min dwell (min) `30` ▸ Analyze. [check in UI: *No dwells meet the current radius/dwell time.*]
- Impact: mark the empty answer.

### S07 · Co-location
- Voice: The third question: who was in the same place at the same time? Co-location pairs two different entities seen within the radius and within a window of minutes. With a one hour window, Analyze finds nothing: these accidents happened on different nights. Widen the window to two weeks, twenty thousand one hundred and sixty minutes, and Analyze again. One pair appears: two accidents at the junction, eight days apart, in March. The question has changed to accidents at the same spot within a fortnight, and you set it on purpose. With phones or vehicles you would keep the window short, minutes, because together means at the same moment.
- Chip: Co-location · Window (min)
- Action: Co-location ▸ Radius (m) `400` ▸ Window (min) `60` ▸ Analyze ▸ hold ▸ Window (min) `20160` ▸ Analyze. [check in UI: *No co-locations in the current radius/window.*; then one pair *ACC-0011 ↔ ACC-0005 · met 1×, first …*]
- Impact: mark the empty answer, then the pair.

### S08 · From the map to a graph
- Voice: Pairs are links, so they can be drawn as a graph. Press View as graph. Who met whom: the entities linked when they were co-located, drawn by the same renderer as Link Analysis. With phones or vehicles, this is where a convoy, a meeting, or a gang that travels together shows up as a group. Close it to return to the map.
- Chip: View as graph · Who met whom
- Action: View as graph ▸ hold on the dialog ▸ Close.
- Impact: mark the dialog's graph.

### S09 · Between a record and the map
- Voice: One more way across. When a point's row names a Case or an Incident, its details offer a graph button, and in Link Analysis, a node that stands for an Incident or a Case offers a map button. The button carries only the record's reference. Draw the next graph, or the next map, and the record is found and centred there; if it is not in that answer, a short message says so. None of the shipped example maps names a Case or an Incident on its rows, so the button does not appear here.
- Chip: graph · map
- Action: click one junction point ▸ hold on its details (no graph button). [check in UI: no shipped Geo Dataset carries `caseId`, `incidentId` or `objectId`; record this beat on a Space whose Geo rows do, or keep it as described]
- Impact: mark the point's details.

### S10 · Try it
- Voice: So: map who and when, then ask where it keeps turning up, where it stayed, and what was together; open pairs as a graph. Your turn. In the same map, set the Entity column to the city, and run Co-location with a radius of four hundred metres and a window of sixty minutes. Why is the list empty? The answer is in the description. Next, in G3, Entity profiles.
- Action: outro card: *Try it* and *Next: G3 · Entity profiles*.
- Impact: none.

## G3_Entity-profiles

> **G3 · Entity profiles** · Standard · Banking and finance Space · the compromised terminal · 5 min · user guide §3.3,
> §6.2

- **Objectives:** open an Entity's profile from the Entities page or from a node; read what the data says about it (seen,
  where, measures, links, related identifiers, lists, Cases, freshness); tell a suspicious entity from a busy one; start an
  Investigation from a profile.
- **Before:** a fresh unzip of `la-standard.zip`, the Banking and finance Space, signed in as Demo Analyst. The seed
  (`tools/seed-la-card.mjs`) left the Case *Compromised terminal at M-317*, linked to the Investigation *Compromised
  terminal*, and the Entity List *Cards to reissue*.
- **Covers (plan §5):** 5.5 Entities ▸ Entity Types ▸ an Entity Type ▸ Open an Entity; 5.2d Open the Entity page; the
  Entity profile (Investigate, Seen: First seen, Last seen, Active days, Rows per day; Where; Measures; Links: Degree, Among
  its type, Link kinds; Related identifiers; Lists and groups: Identity group, Entity Lists, Case history, Risk;
  Freshness; the window line and Show the whole history).
- **Try it:** open the profile of the supermarket M-052. Is it on an Entity List, and does it have a Case history?
  *Answer: no and no. It is the busy look-alike: about 900 cards paid there, none of them later defrauded, so nothing was
  recorded against it (ground truth of `gen-la-card-data.py`).*
- **Check:** what does Among its type tell you that Degree does not? *Answer: where this entity's number of links sits
  in the spread of every entity of its type, so busy is judged against its peers.*
- **Gotchas:** Entity profiles are Standard and up. The type that holds M-317 is expected to be *Card entity*
  (`card_entity`, the type the seed uses); confirm it in the Entities page. A masked type's profile is read by its alias
  and says so; this Space masks nothing.

### S01 · One entity, all of it
- Voice: This is Inspecto LA training, episode G3: Entity profiles. A graph shows an entity among others. Sometimes you want the opposite: one entity, and everything the data says about it, across every Dataset, without drawing anything. That is its profile. In this episode you will open one, read it section by section, compare it with a look-alike, and start an Investigation from it. We are in the Standard edition, in the Banking and finance Space, on the compromised fuel station of F8.
- Action: intro card.
- Impact: none.

### S02 · Two ways in
- Voice: In the rail, open Entities. The Entity Types tab lists the kinds of thing in your data. Open Card entity, the type the card story is drawn as. Its page has a box: Open an Entity. Type M-317, the fuel station, and press Open. The other way in is from the graph: in any node's details, Open the Entity page takes you to the same place.
- Chip: Entities · Open an Entity
- Action: rail ▸ Entities ▸ Entity Types ▸ *Card entity* ▸ Open an Entity `M-317` ▸ Open. [check in UI: the type's label and that M-317 is found under it]
- Impact: mark the Open an Entity box; then the profile's header.

### S03 · Seen
- Voice: The header names the entity, with a link back to its type, and one button, Investigate. The first section is Seen: the first and last day it appears in the data, how many days it was active, and a calendar of rows per day. A fuel station is busy every day of the year, so the calendar alone does not raise a flag. That is exactly why the rest of the profile matters.
- Chip: Seen · Rows per day
- Action: hold on the header ▸ the Seen section: First seen, Last seen, Active days ▸ zoom on Rows per day.
- Impact: mark the four tiles; then the calendar.

### S04 · Where, and Measures
- Voice: Where lists the Datasets that name this entity, and how many rows each. Measures shows the figures those Datasets carry for it, such as the money that passed through it. When a profile is opened from a link with a time window, a line at the top says it is scoped to that window, and Show the whole history removes it.
- Chip: Where · Measures
- Action: open Where ▸ open Measures. [check in UI: which Datasets and Measures M-317 shows]
- Impact: mark the Where table and the Measures table.

### S05 · Links, against its peers
- Voice: Links gives its degree: how many distinct entities it links to. Beside it, Among its type says where that number sits among all entities of the same type. A big number means little until you know whether every fuel station has it. Link kinds breaks the links down by kind. Here is the one that matters: cards that paid at this station and were defrauded weeks later.
- Chip: Links · Degree · Among its type · Link kinds
- Action: open Links ▸ zoom on Degree and Among its type ▸ the Links by kind bars. [check in UI: the link kind `visited_before_fraud` and its label in the bars]
- Impact: mark Among its type; then the fraud link kind's bar.

### S06 · Related identifiers
- Voice: Related identifiers lists the other kinds of entity that share rows with this one, with a count and their keys. Each key is a link to its own profile, so you can walk from the station to what the data ties to it, one profile at a time, without building a graph.
- Chip: Related identifiers
- Action: open Related identifiers ▸ click one key ▸ Back. [check in UI: whether M-317 has related identifiers; if it says *No other Entity Type shares a row with this Entity.*, the voice says that instead]
- Impact: mark the section, then the profile it led to.

### S07 · Lists, Cases and risk
- Voice: Lists and groups gathers what people and rules have already said about it. Its identity group: here, in no group, it resolves to itself. The Entity Lists it is on. And Case history: every Incident and Case that names it. There it is: Compromised terminal at M-317, opened when the analyst found this station. Anyone who opens this profile now sees that it has been looked at, and by which Case.
- Chip: Lists and groups · Case history
- Action: open Lists and groups ▸ hold on Identity group ▸ Case history ▸ Show: all. [check in UI: *in no group (resolves to itself)* and the Case title]
- Impact: mark the identity line, then the Case row.

### S08 · Freshness
- Voice: The last section is Freshness: for each Dataset, the day its data runs to, and a badge saying how fresh that is. Read last seen against the data, not the clock. If the card data stops at the end of September, last seen in September does not mean the station went quiet.
- Chip: Freshness
- Action: open Freshness ▸ hold. [check in UI: the data-as-of line for the card Datasets]
- Impact: mark the data-as-of line and its badge.

### S09 · Busy is not suspicious
- Voice: Now compare. Open the supermarket M-052. About nine hundred cards paid there, far more than at the fuel station, and Among its type puts it near the top. But no link kind says fraud followed, it is on no list, and its Case history is empty. The profile tells a busy entity from a suspicious one at a glance, which is the question every alert really asks.
- Chip: Open an Entity · M-052
- Action: Card entity ▸ Open an Entity `M-052` ▸ Open ▸ Links ▸ Lists and groups ▸ Case history. [check in UI: M-052's degree is far above M-317's; no Case]
- Impact: before/after hold: the two Links sections side by side (video split); mark the empty Case history.

### S10 · Investigate from a profile
- Voice: Back on M-317, press Investigate. Link Analysis opens with a card: Investigate M-317. It will create an Investigation over the card Dataset, start from the station, and expand one degree. It asks for a name and a purpose: why you are investigating, which the Dossier shows. Here an Investigation already exists, so press Cancel; in your own work, this is how a profile becomes a case.
- Chip: Investigate
- Action: Back ▸ M-317 ▸ Investigate ▸ hold on the card (Name, Purpose) ▸ Cancel. [check in UI: the card's sentence *Creates an Investigation over card_links_dataset …*]
- Impact: mark the card's sentence and the Purpose field.

### S11 · Try it
- Voice: So: a profile is everything the data says about one entity: when, where, how much, linked to what and how many, what lists and Cases say, and how fresh it all is. Your turn. Open the profile of the supermarket M-052. Is it on an Entity List, and does it have a Case history? The answer is in the description. Next, in H1, settings for a Space and for you.
- Action: outro card: *Try it* and *Next: H1 · Settings*.
- Impact: none.
