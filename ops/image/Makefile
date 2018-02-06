image-id: packer.json provision.sh
	packer validate packer.json
	packer build packer.json 2>&1 | tee packer-out
	cat packer-out | tail -n1 | sed 's/.*(ID: \([0-9]*\)).*/\1/' | tr -d '\n' | tee image-id
	rm packer-out
