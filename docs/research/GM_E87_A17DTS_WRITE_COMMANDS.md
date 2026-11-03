# A17DTS / ACDelco E87 — recherches sur les commandes bidirectionnelles

Date : 2026-11-03. État : **identifiants d'écriture NON validés**, aucune écriture autorisée.

## Périmètre exact

Opel Astra J 2013, moteur identifié par le calculateur lui-même via GMLAN `1A97 = A17DTS`, famille ACDelco/GM E87. L'utilisateur veut exclusivement :
1. Déclenchement **service** de la régénération FAP avec contrôle des préconditions, suivi et arrêt.
2. Réglage **persistant** de la consigne/offset de ralenti, lecture initiale, sauvegarde et restauration.

Éviter les confusions avec le test temporaire `Engine speed` et les commandes de régénération automatique (statuts PIDs).

## Ce qui est prouvé par le véhicule

Trace GMLAN vérifiée en 0.0.10 :
```
20 (pre) = 60
10 03 = 50 03
AE 00 = EE 00
20 (post) = 60
```
Donc le moteur accepte une initiation diagnostique et l'arrêt global de DeviceControl. Cela ne dit **rien** sur les CPID des deux fonctions à implémenter.

## Sources recoupées

- ScanDoc Opel Astra J E87 (LUE/LUD/LKR/PL1) : dans **Utilities**, `Particulate filter - regeneration` et `Adjust idle speed` ; dans **Actuators test**, `Engine speed` (fonction distincte). Source : https://scandoc.online/last/0/56/19/8/10
- GMW3110 GM Enhanced Diagnostic Test Mode : service `AE` (DeviceControl), champ CPID et octets de commande ; `AE 00` retire le contrôle des actionneurs ; CPID et longueur/effet applicables dans la définition propre au module. Un exemple CPID 01 de commande moteur n'est pas une prescription universelle. https://studylib.net/doc/26158328/gmw3110
- Scapy GMLAN : `0xAE` DeviceControl et réponse `0xEE`, `0x3B` WriteDataByIdentifier avec réponse `0x7B` ; ces services ne sont pas interchangeables. https://scapy.readthedocs.io/en/latest/api/scapy.contrib.automotive.gm.gmlan.html
- OpenDash, retour d'expérience sur Astra J et GDS2 : les catalogues paramétriques ne permettent pas de déduire la table des CPID ; un CPID EGR (`1C`) a été identifié par une **capture de l'outil atelier**. Un balayage de 255 CPID n'est pas probant et peut être dangereux. Ne pas transposer CPID 1C de cet autre ECU à l'E87. https://githubhelp.com/PabloSoage/opendash
- GDS2 fournit des fonctions de contrôle et de configuration et les séquences exécutables doivent correspondre aux variantes ECU / calibration. Guide officiel GM : https://gsitlc.ext.gm.com/userguides/GM_GDS2_User_Guide.pdf
- Opel DPF TSB : la régénération de service n'est pas une alternative systématique à la régénération routière ; précautions thermiques et critères ECU à respecter. https://workshop-manuals.com/vauxhall/astra-j/general_information/technical_service_bulletins/excessive_effort_hard_work/diesel-particle_filter_%28dpf%29_question_and_answer/

## Hypothèses encore non prouvées

- Le CPID (ou autre action OEM) précis pour démarrer, maintenir, arrêter une régénération service sur **cette calibration E87**.
- Les étapes de session, sécurité, messages keep-alive, conditions préalables et retour de statut de la routine.
- L'emplacement et le mécanisme d'écriture **persistante** du ralenti (possible utilitaire d'adaptation, différent de `AE` temporaire).
- Format signé, échelle, bornes, offset zéro et confirmation d'écriture pour le ralenti.
- Le `10 03` reçu n'autorise pas par lui-même toutes les fonctions d'écriture.

## Acquisition documentaire requise, sans essayer des CPID sur le véhicule

1. Obtenir légalement les définitions ECU E87 correspondant à la calibration A17DTS via service information OEM, ou **des traces GDS2/MDI ou Delphi/Autocom déjà enregistrées et étiquetées** pour ces deux fonctions.
2. Pour chaque fonction, confirmer la trame complète requête + acquittement, l'étape de session/sécurité, la charge utile, l'origine ECU (`7E0`/`7E8` ou adressage réel), le timing/keepalive, la fin et l'abandon.
3. Distinguer explicitement démarrer/arrêter régénération et test accélérateur temporaire/écriture offset ralenti persistante.
4. En absence de preuve, ne pas coder de `AE <CPID>` supposé ni de commande de programmation.
5. Ajouter des tests unitaires de trame sur des **captures attestées** avant build.
6. Le système d'écriture final doit lire/vérifier l'état initial, confirmer l'identité exacte, respecter tous les interlocks ECU, prévoir stop/failsafe et vérifier le résultat. Pour le ralenti, stocker la valeur antérieure et vérifier la restauration.

## État d'OBDfree

La branche `feature/auto-update-signing` comprend le monitoring Astra J E87 et les lectures AA. Les boutons d'écriture factices ont été retirés en 0.0.11. La présence de `AE00 → EE00` ne constitue pas une spécification des deux actions demandées.

**Critère de sortie : pas une UI cliquable, mais des séquences de service identifiées et testées sur données fiables, puis implémentées.**
